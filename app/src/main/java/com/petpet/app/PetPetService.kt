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
        val filter = IntentFilter(ACTION_DONE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(doneReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(doneReceiver, filter)
    }

    override fun onDestroy() {
        stopPetPet()
        runCatching { unregisterReceiver(doneReceiver) }
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopPetPet()
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    fun startPetPet() {
        launcherPackages = packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
        active = true
        showNotification()
        // 홈으로 나가자마자 첫 탭부터 막히도록 즉시 오버레이를 올리고, 런처가 앞에 오면 재확인
        showOverlay()
        listOf(300L, 800L, 1500L).forEach { delay ->
            handler.postDelayed({
                if (active && rootInActiveWindow?.packageName?.toString() in launcherPackages) showOverlay()
            }, delay)
        }
        onStateChanged?.invoke()
    }

    fun stopPetPet() {
        val was = active
        active = false
        hideOverlay()
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        if (was) {
            Toast.makeText(this, "petpet 모드가 꺼졌어요", Toast.LENGTH_SHORT).show()
            onStateChanged?.invoke()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!active || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        when {
            // 상단바(알림 창/퀵설정) 등 시스템UI 창이 열리면 petpet 모드 종료 (시스템 창 위에는 오버레이를 못 올림)
            pkg == "com.android.systemui" -> if (!isIgnorableSystemUi(event)) stopPetPet()
            pkg in launcherPackages -> showOverlay()
            // petpet 앱 화면에서는 done 버튼 등을 누를 수 있게 오버레이를 내림 (모드는 유지)
            pkg == packageName -> hideOverlay()
            pkg.contains("inputmethod") -> Unit
            // 그 외 앱/화면(왼쪽 쓸어서 나오는 구글 피드, 실행된 앱 등)이 앞으로 오면 모드 종료
            else -> stopPetPet()
        }
    }

    /** 볼륨 패널/토스트 같은 가벼운 시스템UI 창은 종료 트리거에서 제외 */
    private fun isIgnorableSystemUi(event: AccessibilityEvent): Boolean {
        val text = (event.className.toString() + event.text + event.contentDescription).lowercase()
        return listOf("volume", "toast", "inputmethod").any { it in text }
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
        var icon: Rect? = null
        var widget: Rect? = null
        var bigLabeled: Rect? = null // 위젯 호스트 뷰가 노출되지 않는 런처용 보조 후보
        val r = Rect()

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

        // petpet 자신의 아이콘인지: 라벨 노드(또는 그 부모 몇 단계)가 탭 위치를 포함하는지
        val label = getString(R.string.app_name)
        val own = root.findAccessibilityNodeInfosByText(label).any { node ->
            var cur: AccessibilityNodeInfo? = node
            var hit = false
            repeat(3) {
                cur?.let { c ->
                    c.getBoundsInScreen(r)
                    if (r.contains(px, py) && r.width() <= maxW && r.height() <= maxH) hit = true
                    cur = c.parent
                }
            }
            hit
        }
        IconHit(bounds, own)
    }.getOrNull()

    private fun showNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "petpet", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val done = PendingIntent.getBroadcast(
            this, 1, Intent(ACTION_DONE).setPackage(packageName), flags
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("petpet 모드 실행 중")
            .setContentText("눌러서 앱을 열거나 done으로 끌 수 있어요")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "done", done).build())
            .setOngoing(true)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    companion object {
        var instance: PetPetService? = null
        var onStateChanged: (() -> Unit)? = null
        private const val ACTION_DONE = "com.petpet.app.DONE"
        private const val CHANNEL = "petpet"
        private const val NOTIF_ID = 1
    }
}
