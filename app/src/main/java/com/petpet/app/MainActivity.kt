package com.petpet.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
        }
        val petpet = Button(this).apply {
            text = "petpet"
            textSize = 22f
            setOnClickListener { startPetPet() }
        }
        val done = Button(this).apply {
            text = "done"
            textSize = 22f
            setOnClickListener {
                PetPetService.instance?.stopPetPet()
                refresh()
            }
        }
        val gap = (16 * resources.displayMetrics.density).toInt()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(gap * 2, gap, gap * 2, gap)
            addView(status)
            addView(petpet, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
            addView(done, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
        })
        showIntroOnce()
    }

    private fun showIntroOnce() {
        val prefs = getSharedPreferences("petpet", MODE_PRIVATE)
        if (prefs.getBoolean("intro_seen", false)) return
        AlertDialog.Builder(this)
            .setTitle("petpet 사용 안내")
            .setMessage(
                "• petpet 버튼을 누르면 홈 화면으로 이동하고 petpet 모드가 시작돼요.\n\n" +
                "• 모드 중 홈 화면의 앱 아이콘을 누르면 앱이 실행되지 않고 그 아이콘 위에서 손이 쓰다듬어 줘요.\n\n" +
                "• 스와이프(페이지 넘기기, 전체 앱 열기)는 그대로 동작해요.\n\n" +
                "• petpet 앱 아이콘은 눌러도 정상 실행돼요.\n\n" +
                "• 상단바를 내리면 petpet 상태 알림이 보여요. 누르면 이 앱으로 돌아오고, 앱의 done 버튼이나 알림의 done으로 끌 수 있어요.\n\n" +
                "• 처음 한 번, 접근성 설정에서 petpet 서비스를 켜야 해요."
            )
            .setCancelable(false)
            .setPositiveButton("확인") { _, _ ->
                prefs.edit().putBoolean("intro_seen", true).apply()
                requestNotificationPermission()
            }
            .show()
    }

    override fun onPause() {
        resumed = false
        PetPetService.onStateChanged = null
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        PetPetService.instance?.hideOverlay()
        PetPetService.onStateChanged = { runOnUiThread { refresh() } }
        refresh()
    }

    private fun refresh() {
        val svc = PetPetService.instance
        status.text = when {
            svc == null -> "접근성 서비스가 꺼져 있어요.\npetpet을 누르면 설정으로 이동합니다."
            svc.active -> "petpet 모드 ON"
            else -> "petpet 모드 OFF"
        }
    }

    private fun startPetPet() {
        val svc = PetPetService.instance
        if (svc == null) {
            Toast.makeText(this, "접근성에서 petpet 서비스를 켜주세요", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        requestNotificationPermission()
        svc.startPetPet()
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    companion object {
        /** petpet 앱 화면이 실제로 앞에 있는지 (서비스가 오버레이 내림 여부를 판단할 때 사용) */
        @Volatile var resumed = false
    }
}
