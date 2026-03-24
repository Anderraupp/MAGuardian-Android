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

        tvPrice      = findViewById(R.id.tvMonthlyPrice)
        btnSubscribe = findViewById(R.id.btnSubscribe)
        btnRestore   = findViewById(R.id.btnRestore)
        progressBar  = findViewById(R.id.progressBilling)
        tvLoading    = findViewById(R.id.tvLoadingPlans)
        layoutContent = findViewById(R.id.layoutContent)

        setupListeners()
        initBilling()
    }

    private fun setupListeners() {
        btnSubscribe.setOnClickListener {
            billing.purchase(this)
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

    private fun initBilling() {
        setLoadingState(true)

        billing = BillingManager(this) { isActive ->
            if (isActive) finishWithSuccess()
        }

        billing.connect {
            setLoadingState(false)
            tvPrice.text = "${billing.getMonthlyPrice()}/mês"
        }
    }

    private fun setLoadingState(loading: Boolean) {
        progressBar.visibility    = if (loading) View.VISIBLE else View.GONE
        tvLoading.visibility      = if (loading) View.VISIBLE else View.GONE
        layoutContent.visibility  = if (loading) View.GONE    else View.VISIBLE
        btnSubscribe.isEnabled    = !loading
    }

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
        billing.destroy()
    }
}
