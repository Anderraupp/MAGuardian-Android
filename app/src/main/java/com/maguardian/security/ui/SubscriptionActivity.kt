package com.maguardian.security.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.maguardian.security.R
import com.maguardian.security.billing.BillingManager

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

        // Mostra conteúdo e habilita botão imediatamente com preço padrão
        showContent()

        setupListeners()
        initBilling()
    }

    // ── Exibe o conteúdo sem esperar o billing ────────────────────────────────

    private fun showContent() {
        progressBar.visibility   = View.GONE
        tvLoading.visibility     = View.GONE
        layoutContent.visibility = View.VISIBLE
        btnSubscribe.isEnabled   = true
        tvPrice.text             = "R$ 9,90/mês"
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    private fun setupListeners() {
        btnSubscribe.setOnClickListener {
            when {
                // Produto carregado → inicia fluxo de compra do Play
                billing.monthlyDetails != null -> {
                    billing.purchase(this)
                }
                // Billing conectado mas produto não retornou → produto não publicado ainda
                billing.billingClient.isReady -> {
                    Toast.makeText(
                        this,
                        "Assinatura indisponível no momento. Certifique-se de que o app foi instalado pela Play Store.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                // Billing não conectou → sem Google Play / sem internet
                else -> {
                    Toast.makeText(
                        this,
                        "Não foi possível conectar ao Google Play. Verifique sua conexão e tente novamente.",
                        Toast.LENGTH_LONG
                    ).show()
                    // Tenta reconectar
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
        billing = BillingManager(this) { isActive ->
            if (isActive) finishWithSuccess()
        }

        billing.connect {
            // Atualiza o preço com o valor real do Play Console (se carregou)
            val realPrice = billing.getMonthlyPrice()
            if (realPrice != "R$ 9,90") {
                tvPrice.text = "$realPrice/mês"
            }
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
