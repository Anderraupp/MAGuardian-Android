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

    private lateinit var cardMonthly: View
    private lateinit var cardYearly: View
    private lateinit var tvMonthlyPrice: TextView
    private lateinit var tvYearlyPrice: TextView
    private lateinit var btnSubscribe: Button
    private lateinit var btnRestore: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvLoadingPlans: TextView

    private var selectedSku: String = BillingManager.SKU_MONTHLY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscription)

        bindViews()
        setupListeners()
        initBilling()
    }

    private fun bindViews() {
        cardMonthly    = findViewById(R.id.cardPlanMonthly)
        cardYearly     = findViewById(R.id.cardPlanYearly)
        tvMonthlyPrice = findViewById(R.id.tvMonthlyPrice)
        tvYearlyPrice  = findViewById(R.id.tvYearlyPrice)
        btnSubscribe   = findViewById(R.id.btnSubscribe)
        btnRestore     = findViewById(R.id.btnRestore)
        progressBar    = findViewById(R.id.progressBilling)
        tvLoadingPlans = findViewById(R.id.tvLoadingPlans)
    }

    private fun setupListeners() {
        cardMonthly.setOnClickListener { selectPlan(BillingManager.SKU_MONTHLY) }
        cardYearly.setOnClickListener  { selectPlan(BillingManager.SKU_YEARLY)  }

        btnSubscribe.setOnClickListener {
            val details = when (selectedSku) {
                BillingManager.SKU_MONTHLY -> billing.monthlyDetails
                else                       -> billing.yearlyDetails
            }
            if (details != null) {
                billing.purchase(this, details)
            } else {
                Toast.makeText(this, "Planos ainda carregando, aguarde…", Toast.LENGTH_SHORT).show()
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

    private fun selectPlan(sku: String) {
        selectedSku = sku
        val isMonthly = sku == BillingManager.SKU_MONTHLY

        cardMonthly.isSelected = isMonthly
        cardYearly.isSelected  = !isMonthly

        val monthlyBorder = if (isMonthly) R.drawable.plan_card_selected else R.drawable.plan_card_default
        val yearlyBorder  = if (!isMonthly) R.drawable.plan_card_selected else R.drawable.plan_card_default
        cardMonthly.setBackgroundResource(monthlyBorder)
        cardYearly.setBackgroundResource(yearlyBorder)
    }

    private fun initBilling() {
        setLoadingState(true)

        billing = BillingManager(this) { isActive ->
            if (isActive) {
                finishWithSuccess()
            }
        }

        billing.connect {
            setLoadingState(false)
            updatePrices()
            selectPlan(selectedSku)
        }
    }

    private fun updatePrices() {
        val monthlyPrice = billing.getPriceFor(BillingManager.SKU_MONTHLY)
        val yearlyPrice  = billing.getPriceFor(BillingManager.SKU_YEARLY)
        tvMonthlyPrice.text = if (monthlyPrice != "—") "$monthlyPrice/mês" else "Carregando…"
        tvYearlyPrice.text  = if (yearlyPrice  != "—") "$yearlyPrice/ano"  else "Carregando…"
    }

    private fun setLoadingState(loading: Boolean) {
        progressBar.visibility    = if (loading) View.VISIBLE else View.GONE
        tvLoadingPlans.visibility = if (loading) View.VISIBLE else View.GONE
        cardMonthly.visibility    = if (loading) View.GONE else View.VISIBLE
        cardYearly.visibility     = if (loading) View.GONE else View.VISIBLE
        btnSubscribe.isEnabled    = !loading
    }

    private fun finishWithSuccess() {
        Toast.makeText(this, "✅ Assinatura ativa! Bem-vindo ao M&A Guardian Premium.", Toast.LENGTH_LONG).show()
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
