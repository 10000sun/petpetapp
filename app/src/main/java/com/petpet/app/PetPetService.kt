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
        updateNotification()
        val filter = IntentFilter(ACTION_DONE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(doneReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(doneReceiver, filter)
    }

    private fun shutdown() {
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
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

    /** (x, y) 위치의 앱 아이콘(클릭 가능한 작은 노드) 또는 위젯을 찾는다. 둘 다 아니면 null. */
    fun hitIcon(x: Float, y: Float): IconHit? = runCatching {
        val root = rootInActiveWindow ?: return null
        val px = x.toInt()
        val py = y.toInt()
        val dm = resources.displayMetrics
        val maxW = (160 * dm.density).toInt()
        val maxH = (190 * dm.density).toInt()
        val screenArea = dm.widthPixels.toLong() * dm.heightPixels
        val r = Rect()

        // petpet 자신의 아이콘/상단바 알림이면 탭을 그대로 통과 (앱으로 돌아올 수 있도록).
        // 라벨 노드에서 가장 가까운 클릭 가능한 조상까지 올라가며 탭 위치를 포함하는지 확인
        val own = root.findAccessibilityNodeInfosByText(getString(R.string.app_name)).any { node ->
            var cur: AccessibilityNodeInfo? = node
            var hit = false
            var depth = 0
            while (cur != null && depth < 6) {
                cur.getBoundsInScreen(r)
                if (r.contains(px, py) && r.width().toLong() * r.height() < screenArea / 3) hit = true
                if (cur.isClickable) break
                cur = cur.parent
                depth++
            }
            hit
        }
        if (own) return IconHit(Rect(px, py, px, py), true)

        var icon: Rect? = null
        var widget: Rect? = null
        var bigLabeled: Rect? = null // 위젯 호스트 뷰가 노출되지 않는 런처용 보조 후보

        fun smaller(a: Rect, b: Rect?) = b == null || a.width().toLong() * a.height() < b.width().toLong() * b.height()

        fun walk(n: AccessibilityNodeInfo) {
            n.getBoundsInScreen(r)
            if (!r.contains(px, py)) return
            val area = r.width().toLong() * r.height()
            if (n.className?.contains("AppWidgetHostView") == true) {
                if (smaller(r, widget)) widget = Rect(r)
            } else if ((n.isClickable || n.isLongClickable) && r.width() in 1..maxW && r.height() in 1..maxH) {
                if (smaller(r, icon)) icon = Rect(r)
            } else if ((r.width() > maxW || r.height() > maxH) && area < screenArea / 2 &&
                !(n.contentDescription.isNullOrBlank() && n.text.isNullOrBlank())
            ) {
                if (smaller(r, bigLabeled)) bigLabeled = Rect(r)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)

        val widgetBounds = widget ?: if (icon == null) bigLabeled else null
        if (widgetBounds != null) return IconHit(widgetBounds, isOwn = false, isWidget = true)
        val bounds = icon ?: return null

        IconHit(bounds, false)
    }.getOrNull()

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
    }
}
