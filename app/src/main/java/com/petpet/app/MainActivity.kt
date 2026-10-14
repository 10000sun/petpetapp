package com.petpet.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
    private lateinit var totalCount: TextView
    private lateinit var statsSummary: TextView
    private lateinit var statsList: TextView
    private lateinit var buyStatus: TextView
    private lateinit var buyButton: Button
    private lateinit var adContainer: FrameLayout
    private lateinit var billing: Billing
    private lateinit var panels: List<View>
    private lateinit var tabs: List<Button>
    private var adView: AdView? = null
    private var currentTab = 0

    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()

    private fun label(size: Float, bold: Boolean = false) = TextView(this).apply {
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(Color.DKGRAY)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(text: String, size: Float = 18f, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = size
        setOnClickListener { onClick() }
    }

    private fun column(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(32), dp(16), dp(32), dp(16))
        views.forEach { addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        billing = Billing(this) { onBillingChanged() }

        // 홈: 총 쓰다듬은 횟수 + 시작/종료
        status = label(15f)
        totalCount = label(44f, bold = true)
        val help = button("?", 18f) { showHelp(firstRun = false) }.apply {
            minWidth = 0; minHeight = 0; setPadding(0, 0, 0, 0); contentDescription = "도움말"
        }
        val home = FrameLayout(this).apply {
            addView(column(
                label(14f).apply { text = "총 쓰다듬은 횟수" }, totalCount, status,
                button("petpet", 22f) { startPetPet() },
                button("done", 22f) { PetPetService.instance?.stopPetPet(); refresh() },
            ), FrameLayout.LayoutParams(-1, -1))
            addView(help, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(16), dp(16), 0) })
        }

        // 통계: 쓰다듬은 앱 개수, 총 횟수, 앱별 목록
        statsSummary = label(18f, bold = true)
        statsList = TextView(this).apply { textSize = 16f; setTextColor(Color.DKGRAY); setLineSpacing(0f, 1.3f) }
        val stats = ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(24), dp(24), dp(24))
                addView(statsSummary)
                addView(statsList, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            })
        }

        // 설정: 커피 한 잔 후원(광고 제거), 방침, 도움말
        buyStatus = label(15f)
        buyButton = button("☕ 커피 한 잔 후원하고 광고 끄기") { billing.buy(this) }
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        val settings = column(
            buyStatus, buyButton,
            button("후원 내역 복원", 14f) { billing.restore() },
            button("개인정보처리방침", 14f) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))) },
            button("도움말", 14f) { showHelp(firstRun = false) },
            label(12f).apply { text = "petpet v$version" },
        )

        panels = listOf(home, stats, settings)
        tabs = listOf("홈", "통계", "설정").mapIndexed { i, name -> button(name, 16f) { showTab(i) } }

        // ── 광고 영역 ── 앱 화면 맨 아래에만 둔다 (오버레이/알림/다른 앱 위 금지, 버튼과 간격 유지).
        // 광고가 로드되기 전/실패/광고 제거 구매 시에는 숨긴다.
        adContainer = FrameLayout(this).apply { visibility = View.GONE }

        val host = FrameLayout(this).apply { panels.forEach { addView(it, FrameLayout.LayoutParams(-1, -1)) } }
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            tabs.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(tabBar, LinearLayout.LayoutParams(-1, -2))
            addView(adContainer, LinearLayout.LayoutParams(-1, -2))
        }
        // targetSdk 35+ 는 화면 전체(엣지 투 엣지)로 그려지므로 상태바/내비바 높이만큼 비워 둔다
        root.setOnApplyWindowInsetsListener { v, insets -> applyInsets(v, insets) }
        setContentView(root)

        showTab(0)
        billing.start()
        if (!getSharedPreferences("petpet", MODE_PRIVATE).getBoolean("intro_seen", false)) {
            showHelp(firstRun = true)
        }
        initAds()
    }

    private fun showTab(i: Int) {
        currentTab = i
        panels.forEachIndexed { idx, p -> p.visibility = if (idx == i) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { idx, t -> t.isEnabled = idx != i }
        refresh()
    }

    @Suppress("DEPRECATION")
    private fun applyInsets(v: View, insets: WindowInsets): WindowInsets {
        v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        return insets
    }

    // ── 광고 ──

    /** EEA 등 동의가 필요한 지역은 동의 양식을 거친 뒤에만 광고를 요청한다. 광고 제거 구매자는 건너뜀 */
    private fun initAds() {
        if (Billing.adsRemoved(this)) return
        val info = UserMessagingPlatform.getConsentInformation(this)
        info.requestConsentInfoUpdate(this, ConsentRequestParameters.Builder().build(), {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(this) { if (info.canRequestAds()) loadBanner() }
        }, { if (info.canRequestAds()) loadBanner() })
    }

    private fun loadBanner() {
        if (adView != null || Billing.adsRemoved(this)) return
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

    private fun removeAds() {
        adView?.destroy()
        adView = null
        adContainer.removeAllViews()
        adContainer.visibility = View.GONE
    }

    private fun onBillingChanged() {
        if (Billing.adsRemoved(this)) removeAds()
        refresh()
    }

    // ── 화면 갱신 ──

    private fun refresh() {
        val svc = PetPetService.instance
        status.text = when {
            svc == null -> "접근성 서비스가 꺼져 있어요.\npetpet을 누르면 설정으로 이동합니다."
            svc.active -> "petpet 모드 ON"
            else -> "petpet 모드 OFF"
        }
        val total = PetStats.total(this)
        totalCount.text = "${total}회"

        val apps = PetStats.apps(this)
        statsSummary.text = "쓰다듬은 앱 ${apps.size}개 · 총 ${total}회"
        statsList.text = if (apps.isEmpty()) "아직 쓰다듬은 앱이 없어요" else
            apps.mapIndexed { i, (name, n) -> "${i + 1}. $name — ${n}회" }.joinToString("\n")

        val removed = Billing.adsRemoved(this)
        val price = billing.price
        buyStatus.text = when {
            removed -> "광고가 꺼졌어요. 커피 한 잔 후원 감사합니다 ☕"
            price == null -> "후원 상품을 불러오는 중이에요"
            else -> "커피 한 잔($price)을 후원하면 광고가 사라져요 (일회성 결제)"
        }
        buyButton.isEnabled = !removed && price != null
    }

    /** 처음 실행 시 자동으로, 이후엔 "?"/설정의 도움말로 표시되는 사용 안내 팝업 */
    private fun showHelp(firstRun: Boolean) {
        val builder = AlertDialog.Builder(this)
            .setTitle("petpet 사용 안내")
            .setMessage(
                "• petpet 버튼을 누르면 홈 화면으로 이동하고 petpet 모드가 시작돼요.\n\n" +
                "• 모드 중 홈 화면의 앱 아이콘이나 위젯을 누르면 실행되지 않고 그 위에서 손이 쓰다듬어 줘요.\n\n" +
                "• 스와이프(페이지 넘기기, 전체 앱 열기)는 그대로 동작해요.\n\n" +
                "• petpet 앱 아이콘은 눌러도 정상 실행돼요.\n\n" +
                "• 상단바를 내리면 petpet 상태 알림(실행 중/꺼짐)이 보여요. 누르면 이 앱으로 돌아오고, 앱의 done 버튼이나 알림의 done으로 끌 수 있어요.\n\n" +
                "• 통계 탭에서 쓰다듬은 횟수와 앱 목록을, 설정 탭에서 커피 한 잔 후원(광고 제거)을 볼 수 있어요.\n\n" +
                "• 처음 한 번, 접근성 설정에서 petpet 서비스를 켜야 해요."
            )
            .setNeutralButton("개인정보처리방침") { _, _ ->
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
        billing.end()
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
        billing.restore() // 구매 상태가 바뀌었을 수 있어(환불 등) 확인
        refresh()
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
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
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
