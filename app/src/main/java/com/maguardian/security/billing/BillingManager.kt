package com.maguardian.security.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.maguardian.security.util.PrefsHelper
import kotlinx.coroutines.*

/**
 * Gerencia toda a comunicação com o Google Play Billing.
 * Plano único: mensal (R$ 9,90/mês), sem período de teste.
 */
class BillingManager(
    private val context: Context,
    private val onStatusChanged: (isActive: Boolean) -> Unit
) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "BillingManager"

        /** ID do produto criado no Google Play Console */
        const val SKU_MONTHLY = "ma_guardian_mensal"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    var monthlyDetails: ProductDetails? = null
        private set

    // ── Conexão ──────────────────────────────────────────────────────────────

    fun connect(onReady: (() -> Unit)? = null) {
        if (billingClient.isReady) {
            scope.launch {
                loadProducts()
                refreshStatus()
                withContext(Dispatchers.Main) { onReady?.invoke() }
            }
            return
        }

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.i(TAG, "Billing conectado com sucesso")
                    scope.launch {
                        loadProducts()
                        refreshStatus()
                        withContext(Dispatchers.Main) { onReady?.invoke() }
                    }
                } else {
                    Log.w(TAG, "Erro ao conectar billing: ${result.debugMessage}")
                    // Avisa a UI mesmo em falha para não bloquear o botão indefinidamente
                    scope.launch(Dispatchers.Main) { onReady?.invoke() }
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing desconectado — reconectando...")
                connect(onReady)
            }
        })
    }

    // ── Produto ───────────────────────────────────────────────────────────────

    private suspend fun loadProducts() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(SKU_MONTHLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        val result = billingClient.queryProductDetails(params)
        monthlyDetails = result.productDetailsList?.firstOrNull { it.productId == SKU_MONTHLY }
        Log.i(TAG, "Produto mensal carregado: ${monthlyDetails != null}")
    }

    // ── Status da assinatura ──────────────────────────────────────────────────

    suspend fun refreshStatus(): Boolean {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val result = billingClient.queryPurchasesAsync(params)
        val active = result.purchasesList.any { purchase ->
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED
        }
        PrefsHelper.setSubscriptionActive(context, active)
        withContext(Dispatchers.Main) { onStatusChanged(active) }
        return active
    }

    // ── Compra ───────────────────────────────────────────────────────────────

    fun purchase(activity: Activity) {
        val details = monthlyDetails ?: run {
            Log.w(TAG, "Produto ainda não carregado")
            return
        }
        val offerToken = details.subscriptionOfferDetails
            ?.firstOrNull()?.offerToken ?: run {
            Log.w(TAG, "Nenhum offerToken disponível")
            return
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
            .build()

        billingClient.launchBillingFlow(activity, flowParams)
    }

    // ── Callback de atualização de compra ─────────────────────────────────────

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                        && !purchase.isAcknowledged) {
                        scope.launch { acknowledge(purchase) }
                    }
                }
                scope.launch { refreshStatus() }
            }
            BillingClient.BillingResponseCode.USER_CANCELED ->
                Log.i(TAG, "Compra cancelada pelo usuário")
            else ->
                Log.w(TAG, "Erro na compra (${result.responseCode}): ${result.debugMessage}")
        }
    }

    // ── Confirmação (acknowledge) ─────────────────────────────────────────────

    private suspend fun acknowledge(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        val result = billingClient.acknowledgePurchase(params)
        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            Log.i(TAG, "Compra confirmada: ${purchase.products}")
            refreshStatus()
        }
    }

    // ── Restaurar compras ─────────────────────────────────────────────────────

    fun restorePurchases(onResult: (Boolean) -> Unit) {
        scope.launch {
            val active = refreshStatus()
            withContext(Dispatchers.Main) { onResult(active) }
        }
    }

    // ── Preço formatado ───────────────────────────────────────────────────────

    fun getMonthlyPrice(): String =
        monthlyDetails?.subscriptionOfferDetails
            ?.firstOrNull()?.pricingPhases?.pricingPhaseList
            ?.lastOrNull()?.formattedPrice ?: "R$ 9,90"

    fun destroy() {
        scope.cancel()
        if (billingClient.isReady) billingClient.endConnection()
    }
}
