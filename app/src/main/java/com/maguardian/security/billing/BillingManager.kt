package com.maguardian.security.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.maguardian.security.util.PrefsHelper
import kotlinx.coroutines.*

/**
 * Gerencia toda a comunicação com o Google Play Billing.
 * - Conecta ao serviço de faturamento
 * - Carrega os detalhes dos planos de assinatura
 * - Inicia o fluxo de compra
 * - Verifica e confirma (acknowledges) compras
 * - Notifica o app quando o status da assinatura muda
 */
class BillingManager(
    private val context: Context,
    private val onStatusChanged: (isActive: Boolean) -> Unit
) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "BillingManager"

        /** IDs dos produtos criados no Google Play Console */
        const val SKU_MONTHLY = "maguardian_premium_monthly"
        const val SKU_YEARLY  = "maguardian_premium_yearly"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    var monthlyDetails: ProductDetails? = null
        private set

    var yearlyDetails: ProductDetails? = null
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
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing desconectado — reconectando...")
                connect(onReady)
            }
        })
    }

    // ── Produtos ─────────────────────────────────────────────────────────────

    private suspend fun loadProducts() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(SKU_MONTHLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(SKU_YEARLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        val result = billingClient.queryProductDetails(params)
        result.productDetailsList?.forEach { details ->
            when (details.productId) {
                SKU_MONTHLY -> monthlyDetails = details
                SKU_YEARLY  -> yearlyDetails  = details
            }
        }
        Log.i(TAG, "Produtos carregados — mensal=${monthlyDetails != null} anual=${yearlyDetails != null}")
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

    fun purchase(activity: Activity, details: ProductDetails) {
        val offerToken = details.subscriptionOfferDetails
            ?.firstOrNull()?.offerToken ?: run {
            Log.w(TAG, "Nenhum offerToken disponível para ${details.productId}")
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

    fun getPriceFor(sku: String): String {
        val details = if (sku == SKU_MONTHLY) monthlyDetails else yearlyDetails
        return details?.subscriptionOfferDetails
            ?.firstOrNull()?.pricingPhases?.pricingPhaseList
            ?.lastOrNull()?.formattedPrice ?: "—"
    }

    fun destroy() {
        scope.cancel()
        if (billingClient.isReady) billingClient.endConnection()
    }
}
