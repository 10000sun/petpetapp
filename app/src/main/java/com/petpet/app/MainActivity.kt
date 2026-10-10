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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.net.Uri
import android.view.View
import android.view.WindowInsets
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var adContainer: FrameLayout
    private var adView: AdView? = null

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
        val d = resources.displayMetrics.density
        val gap = (16 * d).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(gap * 2, gap, gap * 2, gap)
            addView(status)
            addView(petpet, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
            addView(done, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
        }
        // 오른쪽 위 "?" 버튼: 도움말 다시 보기
        val help = Button(this).apply {
            text = "?"
            textSize = 18f
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            contentDescription = "도움말"
            setOnClickListener { showHelp(firstRun = false) }
        }
        val size = (44 * d).toInt()
        // ── 광고 영역 ── 앱 메인 화면 하단에만 둔다 (오버레이/알림/다른 앱 위 금지, 버튼과 간격 유지).
        // 광고가 로드되기 전/실패 시에는 숨긴다.
        val adHeight = (60 * d).toInt()
        adContainer = FrameLayout(this).apply { visibility = View.GONE }
        content.setPadding(gap * 2, gap, gap * 2, gap + adHeight + gap)
        val root = FrameLayout(this)
        // targetSdk 35+ 는 화면 전체(엣지 투 엣지)로 그려지므로 상태바/내비바 높이만큼 비워 둔다
        root.setOnApplyWindowInsetsListener { v, insets -> applyInsets(v, insets) }
        setContentView(root.apply {
            addView(content, FrameLayout.LayoutParams(-1, -1))
            addView(adContainer, FrameLayout.LayoutParams(-1, adHeight, Gravity.BOTTOM))
            addView(help, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
                setMargins(0, gap, gap, 0)
            })
        })

        if (!getSharedPreferences("petpet", MODE_PRIVATE).getBoolean("intro_seen", false)) {
            showHelp(firstRun = true)
        }
        initAds()
    }

    @Suppress("DEPRECATION")
    private fun applyInsets(v: View, insets: WindowInsets): WindowInsets {
        v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        return insets
    }

    /** EEA 등 동의가 필요한 지역은 동의 양식을 거친 뒤에만 광고를 요청한다 */
    private fun initAds() {
        val info = UserMessagingPlatform.getConsentInformation(this)
        info.requestConsentInfoUpdate(this, ConsentRequestParameters.Builder().build(), {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(this) { if (info.canRequestAds()) loadBanner() }
        }, { if (info.canRequestAds()) loadBanner() })
    }

    private fun loadBanner() {
        if (adView != null) return
        MobileAds.initialize(this)
        val view = AdView(this).apply {
            setAdSize(AdSize.BANNER)
            adUnitId = getString(R.string.admob_banner_id)
            adListener = object : AdListener() {
                override fun onAdLoaded() { adContainer.visibility = View.VISIBLE }
                override fun onAdFailedToLoad(error: LoadAdError) { adContainer.visibility = View.GONE }
            }
        }
        adContainer.addView(view, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        adView = view
        view.loadAd(AdRequest.Builder().build())
    }

    /** 처음 실행 시 자동으로, 이후엔 "?" 버튼으로 표시되는 사용 안내 팝업 */
    private fun showHelp(firstRun: Boolean) {
        val builder = AlertDialog.Builder(this)
            .setTitle("petpet 사용 안내")
            .setMessage(
                "• petpet 버튼을 누르면 홈 화면으로 이동하고 petpet 모드가 시작돼요.\n\n" +
                "• 모드 중 홈 화면의 앱 아이콘이나 위젯을 누르면 실행되지 않고 그 위에서 손이 쓰다듬어 줘요.\n\n" +
                "• 스와이프(페이지 넘기기, 전체 앱 열기)는 그대로 동작해요.\n\n" +
                "• petpet 앱 아이콘은 눌러도 정상 실행돼요.\n\n" +
                "• 상단바를 내리면 petpet 상태 알림(실행 중/꺼짐)이 보여요. 누르면 이 앱으로 돌아오고, 앱의 done 버튼이나 알림의 done으로 끌 수 있어요.\n\n" +
                "• 처음 한 번, 접근성 설정에서 petpet 서비스를 켜야 해요."
            )
        builder.setNeutralButton("개인정보처리방침") { _, _ ->
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)))
        }
        if (firstRun) {
            builder.setCancelable(false).setPositiveButton("확인") { _, _ ->
                getSharedPreferences("petpet", MODE_PRIVATE).edit().putBoolean("intro_seen", true).apply()
                requestNotificationPermission()
            }
        } else {
            builder.setPositiveButton("닫기", null)
        }
        builder.show()
    }

    override fun onDestroy() {
        adView?.destroy()
        super.onDestroy()
    }

    override fun onPause() {
        adView?.pause()
        resumed = false
        PetPetService.onStateChanged = null
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        adView?.resume()
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
            // Play 정책: 접근성 권한 사용 목적을 명확히 고지하고 동의를 받은 뒤에만 설정으로 보낸다
            AlertDialog.Builder(this)
                .setTitle("접근성 서비스 사용 동의")
                .setMessage(
                    "petpet은 접근성 서비스를 사용해 홈 화면 위에 투명한 화면을 띄우고, 앱 아이콘/위젯을 누를 때 " +
                    "쓰다듬는 효과를 보여줍니다.\n\n" +
                    "• 터치한 위치의 아이콘 이름과 위치를 읽습니다.\n" +
                    "• 이 정보는 기기 안에서만 쓰이며 저장하거나 외부로 전송하지 않습니다.\n" +
                    "• 광고에는 사용하지 않습니다.\n\n" +
                    "동의하시면 접근성 설정으로 이동합니다. 설정에서 petpet을 켜주세요."
                )
                .setPositiveButton("동의하고 이동") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("취소", null)
                .show()
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
        // GitHub Pages(저장소 Settings → Pages → main /docs)로 호스팅되는 개인정보처리방침
        private const val PRIVACY_URL = "https://10000sun.github.io/petpetapp/privacy-policy.html"
    }
}
