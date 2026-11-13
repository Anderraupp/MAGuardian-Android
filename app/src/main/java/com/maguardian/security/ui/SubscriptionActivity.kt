package com.maguardian.security.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.maguardian.security.R
import com.maguardian.security.billing.BillingManager
import com.maguardian.security.util.PrefsHelper

class SubscriptionActivity : AppCompatActivity() {
    override fun onResume() {
        super.onResume()
        GuardianAppearance.refreshIfChanged(this)
    }


    private lateinit var billing: BillingManager

    private lateinit var tvPrice: TextView
    private lateinit var btnSubscribe: Button
    private lateinit var btnRestore: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvLoading: TextView
    private lateinit var layoutContent: View
    private var purchaseRequested = false
    private var restoring = false
    private var autoRestoreAttempted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        GuardianAppearance.apply(this)
        super.onCreate(savedInstanceState)
        ScreenInsets.configure(window)
        setContentView(R.layout.activity_subscription)
        ScreenInsets.apply(this, light = GuardianAppearance.isLight(this))
        GuardianNavigation.bind(this)

        tvPrice       = findViewById(R.id.tvMonthlyPrice)
        btnSubscribe  = findViewById(R.id.btnSubscribe)
        btnRestore    = findViewById(R.id.btnRestore)
        progressBar   = findViewById(R.id.progressBilling)
        tvLoading     = findViewById(R.id.tvLoadingPlans)
        layoutContent = findViewById(R.id.layoutContent)

        showContent()
        setupListeners()
        initBilling()
    }

    // ── Exibe o conteúdo imediatamente ──────────────────────────────────────

    private fun showContent() {
        progressBar.visibility   = View.VISIBLE
        tvLoading.visibility     = View.VISIBLE
        layoutContent.visibility = View.VISIBLE
        btnSubscribe.isEnabled   = false
        tvPrice.text             = "Consultando preço…"
    }

    private fun updatePrice() {
        val offer = billing.offerPresentation()
        val active = PrefsHelper.isSubscriptionActive(this)
        tvPrice.text = offer?.priceLabel ?: "Preço indisponível"
        findViewById<TextView>(R.id.tvPremiumState).text = if (active) "ASSINATURA ATIVA" else "PLANO MENSAL"
        findViewById<TextView>(R.id.tvPlayOffer).apply {
            visibility = if (!active && !offer?.conditions.isNullOrBlank()) View.VISIBLE else View.GONE
            text = offer?.conditions.orEmpty()
        }
        btnSubscribe.text = when {
            active -> "Gerenciar assinatura"
            offer == null -> "Consultar plano novamente"
            offer.sevenDayTrial -> "Começar com Google Play"
            else -> "Assinar com Google Play"
        }
        btnSubscribe.isEnabled = true
        progressBar.visibility = View.GONE
        tvLoading.visibility = if (offer == null && !active) View.VISIBLE else View.GONE
        tvLoading.text = "Não foi possível consultar o plano. Confira a conexão e a disponibilidade na Google Play."
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    private fun setupListeners() {
        findViewById<Button>(R.id.btnSubscriptionBack).setOnClickListener { finish() }
        btnSubscribe.setOnClickListener {
            if (PrefsHelper.isSubscriptionActive(this)) {
                GuardianExternal.web(this, "https://play.google.com/store/account/subscriptions?sku=${BillingManager.SKU_MONTHLY}&package=$packageName")
                return@setOnClickListener
            }
            when {
                billing.offerPresentation() != null -> {
                    // Produto já carregado — inicia compra imediatamente
                    purchaseRequested = true
                    billing.purchase(this)
                }
                billing.billingClient.isReady -> {
                    // Produto ainda carregando — recarrega e abre compra automaticamente
                    progressBar.visibility = View.VISIBLE
                    purchaseRequested = true
                    billing.reloadAndPurchase(this) { loaded ->
                        progressBar.visibility = View.GONE
                        if (!loaded) purchaseRequested = false
                        updatePrice()
                    }
                }
                else -> {
                    // Billing não conectado — reconecta e tenta de novo
                    progressBar.visibility = View.VISIBLE
                    initBilling()
                }
            }
        }

        btnRestore.setOnClickListener {
            restoring = true
            btnRestore.isEnabled = false
            progressBar.visibility = View.VISIBLE
            billing.restorePurchases { active ->
                progressBar.visibility = View.GONE
                btnRestore.isEnabled = true
                if (active) {
                    finishWithSuccess()
                } else {
                    restoring = false
                    Toast.makeText(this, "Nenhuma assinatura ativa encontrada.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        findViewById<TextView>(R.id.tvTerms).setOnClickListener {
            GuardianExternal.web(this, GuardianExternal.TERMS)
        }
        findViewById<TextView>(R.id.tvPrivacy).setOnClickListener {
            GuardianExternal.web(this, GuardianExternal.PRIVACY)
        }
    }

    // ── Billing ───────────────────────────────────────────────────────────────

    private fun initBilling() {
        if (!::billing.isInitialized) {
            billing = BillingManager(this) { isActive ->
                if (isActive && (purchaseRequested || restoring)) finishWithSuccess()
                else if (::tvPrice.isInitialized) updatePrice()
            }
        }
        showContent()
        billing.connect {
            progressBar.visibility = View.GONE
            updatePrice()
            if (intent.getBooleanExtra("guardian_restore", false) && !autoRestoreAttempted && !isFinishing) {
                autoRestoreAttempted = true
                btnRestore.performClick()
            }
            // Se produto carregou após reconexão e estava em espera, tudo pronto
        }
    }

    // ── Utilitários ───────────────────────────────────────────────────────────

    private fun finishWithSuccess() {
        if (isFinishing) return
        Toast.makeText(this, "✅ Bem-vindo ao M&A Guardian Premium!", Toast.LENGTH_LONG).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::billing.isInitialized) billing.destroy()
    }
}
