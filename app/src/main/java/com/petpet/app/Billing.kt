package com.petpet.app

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * "커피 한 잔 후원" 일회성 인앱 상품(비소비성). 구매하면 광고가 사라진다.
 * Play Console에서 상품 ID [PRODUCT_ID]로 "인앱 상품(일회성)"을 만들어 활성화해야 한다.
 * shortcut: 구매 여부를 서버 검증 없이 기기 안에서만 판단, 서버 검증이 필요해지면 purchaseToken을 백엔드로 검증
 */
class Billing(private val ctx: Context, private val onChange: () -> Unit) : PurchasesUpdatedListener {

    private val client = BillingClient.newBuilder(ctx)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()
    private var details: ProductDetails? = null

    /** 현지 통화 가격 문자열, 상품을 아직 못 불러왔으면 null */
    val price: String? get() = details?.oneTimePurchaseOfferDetails?.formattedPrice

    fun start() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) return
                queryDetails()
                restore()
            }

            override fun onBillingServiceDisconnected() {} // 다음 start()/화면 진입 때 다시 연결
        })
    }

    fun end() = client.endConnection()

    private fun queryDetails() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT_ID).setProductType(BillingClient.ProductType.INAPP).build()
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()) { result, list ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                details = list.firstOrNull()
                onChange()
            }
        }
    }

    /** 이미 구매했는지 확인 (재설치/기기 변경 후 복원 포함) */
    fun restore() {
        if (!client.isReady) return start()
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result, list ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) handle(list, authoritative = true)
        }
    }

    fun buy(activity: Activity) {
        val d = details ?: return
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()))
            .build()
        client.launchBillingFlow(activity, params)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) handle(purchases, authoritative = false)
    }

    private fun handle(purchases: List<Purchase>, authoritative: Boolean) {
        val owned = purchases.filter { PRODUCT_ID in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
        // 3일 안에 승인(acknowledge)하지 않으면 Play가 자동 환불한다
        owned.filter { !it.isAcknowledged }.forEach {
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(it.purchaseToken).build()) {}
        }
        // 조회 결과는 구매 없음(환불 등)도 반영, 구매 콜백은 구매가 있을 때만 켠다
        if (owned.isNotEmpty() || authoritative) setAdsRemoved(ctx, owned.isNotEmpty())
        onChange()
    }

    companion object {
        const val PRODUCT_ID = "coffee_remove_ads"

        fun adsRemoved(ctx: Context) = ctx.getSharedPreferences("petpet", Context.MODE_PRIVATE).getBoolean("ads_removed", false)

        private fun setAdsRemoved(ctx: Context, v: Boolean) =
            ctx.getSharedPreferences("petpet", Context.MODE_PRIVATE).edit().putBoolean("ads_removed", v).apply()
    }
}
