package com.petpet.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * petpet 모드 중 홈(런처) 화면 위에 투명 오버레이를 띄워 터치를 가로챈다.
 * - 앱 아이콘 탭: 실행 대신 그 아이콘 위에만 쓰다듬는 손 애니메이션 (아이콘이 아닌 곳은 무시)
 * - 스와이프: 오버레이가 받은 제스처를 그대로 다시 재생해 런처에 전달
 * - petpet 앱 자신의 아이콘: 탭을 그대로 통과 (앱으로 돌아올 수 있도록)
 */
class PetPetService : AccessibilityService() {

    var active = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var overlay: PetOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var launcherPackages: Set<String> = emptySet()

    private val doneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = stopPetPet()
    }

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        worker = Executors.newSingleThreadExecutor()
        // 손 움짤 프레임을 미리 로드 (오버레이 생성/첫 애니메이션 때 메인 스레드가 막히지 않도록)
        runBackground { PetOverlayView.loadFrames(this) }
        updateNotification()
        val filter = IntentFilter(ACTION_DONE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(doneReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(doneReceiver, filter)
    }

    private fun shutdown() {
        worker?.shutdown()
        worker = null
        active = false
        hideOverlay()
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    override fun onDestroy() {
        shutdown()
        runCatching { unregisterReceiver(doneReceiver) }
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    fun startPetPet() {
        launcherPackages = packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
        active = true
        updateNotification()
        toast("petpet 모드가 켜졌어요")
        // 홈으로 나가자마자 첫 탭부터 막히도록 즉시 오버레이를 올리고, 런처가 앞에 오면 재확인
        showOverlay()
        listOf(300L, 800L, 1500L).forEach { delay ->
            handler.postDelayed({
                if (active && rootInActiveWindow?.packageName?.toString() in launcherPackages) showOverlay()
            }, delay)
        }
        onStateChanged?.invoke()
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    fun stopPetPet() {
        val was = active
        active = false
        hideOverlay()
        updateNotification()
        if (was) {
            toast("petpet 모드가 꺼졌어요")
            onStateChanged?.invoke()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!active || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        when {
            pkg in launcherPackages -> showOverlay()
            // 시스템UI(상단바 등)/키보드 창은 무시 — 모드는 그대로 유지
            pkg == "com.android.systemui" || pkg.contains("inputmethod") -> Unit
            // petpet 앱이나 다른 앱/화면(구글 피드 등)이 앞이면 터치를 막지 않도록 오버레이만 내림 (모드는 유지)
            else -> hideOverlay()
        }
    }

    private fun showOverlay() {
        if (overlay != null) return
        val view = PetOverlayView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        runCatching { wm.addView(view, lp) }.onSuccess {
            overlay = view
            overlayParams = lp
        }
    }

    fun hideOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
        overlayParams = null
    }

    /** 오버레이를 잠시 터치 불가로 만들고, 같은 제스처를 아래 창(런처)에 재생한다. */
    fun replay(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val lp = overlayParams ?: return
        val view = overlay ?: return
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(view, lp) }
        val restore = Runnable {
            if (overlay === view) {
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                runCatching { wm.updateViewLayout(view, lp) }
            }
        }
        handler.postDelayed({
            val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(30, 1500))
            val ok = dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) = restore.run()
                    override fun onCancelled(g: GestureDescription?) = restore.run()
                },
                null
            )
            if (!ok) restore.run()
        }, 40)
    }

    class IconHit(val bounds: Rect, val isOwn: Boolean, val isWidget: Boolean = false)

    @Volatile private var worker: ExecutorService? = null

    /** 종료된 executor에 던져도 크래시하지 않도록 보호. 실행하지 못했으면 false. */
    private fun runBackground(block: () -> Unit): Boolean {
        val w = worker ?: return false
        return try {
            w.execute { block() }
            true
        } catch (e: RejectedExecutionException) {
            false
        }
    }

    /** 접근성 트리 탐색은 IPC라 느리므로 백그라운드에서 수행하고 결과만 메인 스레드로 돌려준다. */
    fun hitIconAsync(x: Float, y: Float, callback: (IconHit?) -> Unit) {
        val queued = runBackground {
            val hit = hitIcon(x, y)
            handler.post { callback(hit) }
        }
        if (!queued) callback(null)
    }

    /** petpet 아이콘 라벨("petpet", "petpet, 알림 1개") 또는 상태 알림 제목("petpet 모드 …") 인지 */
    private fun isOwnText(cs: CharSequence?): Boolean {
        val t = cs?.toString()?.trim()?.lowercase() ?: return false
        val label = getString(R.string.app_name).lowercase()
        return t == label || t.startsWith("$label,") || t.startsWith("$label 모드")
    }

    private fun hasOwnLabel(n: AccessibilityNodeInfo, depth: Int): Boolean {
        if (isOwnText(n.text) || isOwnText(n.contentDescription)) return true
        if (depth == 0) return false
        for (i in 0 until n.childCount) {
            val c = runCatching { n.getChild(i) }.getOrNull() ?: continue
            if (hasOwnLabel(c, depth - 1)) return true
        }
        return false
    }

    /**
     * (x, y) 위치의 대상을 한 번의 트리 탐색으로 찾는다 (탭 위치를 포함하는 노드만 내려감).
     * 우선순위: 위젯 → petpet 자신의 아이콘/알림(통과) → 그 외 아이콘/라벨 달린 큰 영역
     */
    private fun hitIcon(x: Float, y: Float): IconHit? = try {
        findHit(x, y)
    } catch (e: Exception) {
        Log.w(TAG, "hitIcon failed", e)
        null
    }

    private fun findHit(x: Float, y: Float): IconHit? {
        val root = rootInActiveWindow ?: return null
        val px = x.toInt()
        val py = y.toInt()
        val dm = resources.displayMetrics
        val maxW = (160 * dm.density).toInt()
        val maxH = (190 * dm.density).toInt()
        val screenArea = dm.widthPixels.toLong() * dm.heightPixels
        var icon: Rect? = null
        var widget: Rect? = null
        var bigLabeled: Rect? = null // 위젯 호스트 뷰가 노출되지 않는 런처용 보조 후보
        var clickNode: AccessibilityNodeInfo? = null // 탭 위치를 포함하는 가장 작은 클릭 가능 노드
        var clickArea = Long.MAX_VALUE

        fun smaller(a: Rect, b: Rect?) = b == null || a.width().toLong() * a.height() < b.width().toLong() * b.height()

        fun walk(n: AccessibilityNodeInfo) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (!r.contains(px, py)) return
            val area = r.width().toLong() * r.height()
            val clickable = n.isClickable || n.isLongClickable
            if (clickable && area < screenArea / 3 && area < clickArea) {
                clickNode = n
                clickArea = area
            }
            if (n.className?.contains("AppWidgetHostView") == true) {
                if (smaller(r, widget)) widget = r
            } else if (clickable && r.width() in 1..maxW && r.height() in 1..maxH) {
                if (smaller(r, icon)) icon = r
            } else if ((r.width() > maxW || r.height() > maxH) && area < screenArea / 2 &&
                !(n.contentDescription.isNullOrBlank() && n.text.isNullOrBlank())
            ) {
                if (smaller(r, bigLabeled)) bigLabeled = r
            }
            for (i in 0 until n.childCount) {
                // 트리가 도중에 바뀌어 일부 노드가 무효해져도 나머지 탐색은 계속
                val child = try { n.getChild(i) } catch (e: Exception) { null } ?: continue
                try { walk(child) } catch (e: Exception) { Log.w(TAG, "walk failed", e) }
            }
        }
        walk(root)

        widget?.let { return IconHit(it, isOwn = false, isWidget = true) }
        // 가장 작은 클릭 가능 노드(=실제로 눌린 항목)가 petpet 자신의 것일 때만 통과
        if (clickNode?.let { hasOwnLabel(it, 2) } == true) return IconHit(Rect(px, py, px, py), true)
        if (icon == null) bigLabeled?.let { return IconHit(it, isOwn = false, isWidget = true) }
        return icon?.let { IconHit(it, false) }
    }

    /** 상단바 알림: 현재 petpet 모드 상태를 보여주고, 누르면 petpet 앱으로 돌아간다. */
    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "petpet", NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(if (active) "petpet 모드 실행 중" else "petpet 모드 꺼짐")
            .setContentText("눌러서 petpet 앱으로 돌아가기")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (active) {
            val done = PendingIntent.getBroadcast(
                this, 1, Intent(ACTION_DONE).setPackage(packageName), flags
            )
            b.addAction(Notification.Action.Builder(null, "done", done).build())
        }
        nm.notify(NOTIF_ID, b.build())
    }

    companion object {
        var instance: PetPetService? = null
        var onStateChanged: (() -> Unit)? = null
        private const val ACTION_DONE = "com.petpet.app.DONE"
        private const val CHANNEL = "petpet"
        private const val NOTIF_ID = 1
        private const val TAG = "PetPetService"
    }
}
