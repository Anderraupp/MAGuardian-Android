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

    // ── Exibe o conteúdo imediatamente ──────────────────────────────────────

    private fun showContent() {
        progressBar.visibility   = View.GONE
        tvLoading.visibility     = View.GONE
        layoutContent.visibility = View.VISIBLE
        btnSubscribe.isEnabled   = true
        tvPrice.text             = "R$ 9,99/mês"
    }

    private fun updatePrice() {
        val realPrice = billing.getMonthlyPrice()
        if (realPrice != "R$ 9,99") tvPrice.text = "$realPrice/mês"
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    private fun setupListeners() {
        btnSubscribe.setOnClickListener {
            when {
                billing.monthlyDetails != null -> {
                    // Produto já carregado — inicia compra imediatamente
                    billing.purchase(this)
                }
                billing.billingClient.isReady -> {
                    // Produto ainda carregando — recarrega e abre compra automaticamente
                    progressBar.visibility = View.VISIBLE
                    billing.reloadAndPurchase(this) { loaded ->
                        progressBar.visibility = View.GONE
                        if (loaded) updatePrice()
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
        showContent()
        billing.connect {
            progressBar.visibility = View.GONE
            updatePrice()
            // Se produto carregou após reconexão e estava em espera, tudo pronto
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
