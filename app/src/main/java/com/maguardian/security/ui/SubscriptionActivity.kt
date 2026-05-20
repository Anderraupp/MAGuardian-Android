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

    private lateinit var billing: BillingManager

    private lateinit var tvPrice: TextView
    private lateinit var btnSubscribe: Button
    private lateinit var btnRestore: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvLoading: TextView
    private lateinit var layoutContent: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscription)

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

    // ── Exibe o conteúdo aguardando o billing carregar ───────────────────────

    private fun showLoading() {
        progressBar.visibility   = View.VISIBLE
        tvLoading.visibility     = View.VISIBLE
        tvLoading.text           = "Conectando ao Google Play..."
        layoutContent.visibility = View.VISIBLE
        btnSubscribe.isEnabled   = false
        tvPrice.text             = "R$ 9,99/mês"
    }

    private fun showReady() {
        progressBar.visibility = View.GONE
        tvLoading.visibility   = View.GONE
        btnSubscribe.isEnabled = true
        val realPrice = billing.getMonthlyPrice()
        tvPrice.text = if (realPrice != "R$ 9,99") "$realPrice/mês" else "R$ 9,99/mês"
    }

    private fun showBillingError() {
        progressBar.visibility = View.GONE
        tvLoading.visibility   = View.VISIBLE
        tvLoading.text         = "Toque em Assinar para tentar novamente"
        btnSubscribe.isEnabled = true
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    private fun setupListeners() {
        btnSubscribe.setOnClickListener {
            when {
                billing.monthlyDetails != null -> {
                    billing.purchase(this)
                }
                billing.billingClient.isReady -> {
                    // Produto não carregou — tenta recarregar e comprar
                    showLoading()
                    billing.reloadAndPurchase(this) { loaded ->
                        if (loaded) showReady() else showBillingError()
                    }
                }
                else -> {
                    showLoading()
                    initBilling()
                }
            }
        }

        btnRestore.setOnClickListener {
            btnRestore.isEnabled = false
            progressBar.visibility = View.VISIBLE
            billing.restorePurchases { active ->
                progressBar.visibility = View.GONE
                btnRestore.isEnabled = true
                if (active) {
                    Toast.makeText(this, "✅ Assinatura restaurada com sucesso!", Toast.LENGTH_LONG).show()
                    finishWithSuccess()
                } else {
                    Toast.makeText(this, "Nenhuma assinatura ativa encontrada.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        findViewById<TextView>(R.id.tvTerms).setOnClickListener {
            openUrl("https://anderraupp.github.io/MAGuardian-Android/terms.html")
        }
        findViewById<TextView>(R.id.tvPrivacy).setOnClickListener {
            openUrl("https://anderraupp.github.io/MAGuardian-Android/privacy.html")
        }
    }

    // ── Billing ───────────────────────────────────────────────────────────────

    private fun initBilling() {
        if (!::billing.isInitialized) {
            billing = BillingManager(this) { isActive ->
                if (isActive) finishWithSuccess()
            }
        }
        showLoading()
        billing.connect {
            if (billing.monthlyDetails != null) showReady() else showBillingError()
        }
    }

    // ── Utilitários ───────────────────────────────────────────────────────────

    private fun finishWithSuccess() {
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
