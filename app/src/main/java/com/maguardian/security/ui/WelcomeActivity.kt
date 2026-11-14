package com.maguardian.security.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.maguardian.security.R
import com.maguardian.security.billing.BillingManager
import com.maguardian.security.util.PrefsHelper

class WelcomeActivity : AppCompatActivity() {
    private var billing: BillingManager? = null
    private val restoreLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK && PrefsHelper.isSubscriptionActive(this)) enterApp()
    }
    private fun enterApp() {
        getSharedPreferences("guardian_appearance", 0).edit().putBoolean("welcome_seen", true).apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        GuardianAppearance.apply(this)
        super.onCreate(savedInstanceState)
        if (getSharedPreferences("guardian_appearance", 0).getBoolean("welcome_seen", false)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        ScreenInsets.configure(window)
        setContentView(R.layout.activity_welcome)
        ScreenInsets.apply(this, GuardianAppearance.isLight(this))
        findViewById<View>(R.id.btnWelcomeStart).setOnClickListener {
            enterApp()
        }
        findViewById<View>(R.id.btnWelcomeRestore).setOnClickListener {
            restoreLauncher.launch(Intent(this, SubscriptionActivity::class.java).putExtra("guardian_restore", true))
        }
        findViewById<View>(R.id.btnWelcomePrivacy).setOnClickListener { GuardianExternal.web(this, GuardianExternal.PRIVACY) }
        val manager = BillingManager(this) {}
        billing = manager
        manager.connect {
            if (!isFinishing && !isDestroyed) {
                val offer = manager.offerPresentation()
                findViewById<TextView>(R.id.tvWelcomeOffer).text = when {
                    PrefsHelper.isSubscriptionActive(this) -> "Sua assinatura foi reconhecida. Comece a usar."
                    offer != null && offer.conditions.isNotBlank() -> offer.conditions
                    offer != null -> "Plano ${offer.priceLabel}.\nCondições confirmadas na Google Play."
                    else -> "Consulte os planos e as condições na Google Play."
                }
            }
        }
    }
    override fun onDestroy() { billing?.destroy(); super.onDestroy() }
}
