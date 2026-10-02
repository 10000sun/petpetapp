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
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * petpet 모드 중 홈(런처) 화면 위에 투명 오버레이를 띄워 터치를 가로챈다.
 * - 탭: 앱 실행 대신 쓰다듬기 애니메이션
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
    }

    fun stopPetPet() {
        active = false
        hideOverlay()
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!active || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        when {
            pkg in launcherPackages -> showOverlay()
            // 상태바/키보드 등 시스템 창은 무시, 그 외 앱이 앞으로 오면 오버레이 제거
            pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg == packageName -> Unit
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

    private fun hideOverlay() {
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

    /** (x, y) 위치에 petpet 앱 자신의 런처 아이콘이 있는지 확인한다. */
    fun isOwnIcon(x: Float, y: Float): Boolean = runCatching {
        val label = getString(R.string.app_name)
        val root = rootInActiveWindow ?: return false
        root.findAccessibilityNodeInfosByText(label).any { node ->
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            r.contains(x.toInt(), y.toInt())
        }
    }.getOrDefault(false)

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
        private const val ACTION_DONE = "com.petpet.app.DONE"
        private const val CHANNEL = "petpet"
        private const val NOTIF_ID = 1
    }
}
