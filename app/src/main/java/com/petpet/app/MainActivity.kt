package com.petpet.app

import android.Manifest
import android.app.Activity
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
    }

    override fun onResume() {
        super.onResume()
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
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        svc.startPetPet()
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
