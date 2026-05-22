package com.maguardian.security.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.maguardian.security.R
import com.maguardian.security.util.LinkChecker

class SafeLinkActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        val url = intent?.data?.toString() ?: run { finish(); return }

        val result = LinkChecker.check(url)

        // Safe — pass directly to real browser without showing any UI
        if (result.score < 20) {
            openInBrowser(url)
            finish()
            return
        }

        // Suspicious or fraudulent — show warning screen
        setContentView(R.layout.activity_safe_link)

        val tvIcon      = findViewById<TextView>(R.id.tvSafeIcon)
        val tvTitle     = findViewById<TextView>(R.id.tvSafeTitle)
        val tvSubtitle  = findViewById<TextView>(R.id.tvSafeSubtitle)
        val tvUrl       = findViewById<TextView>(R.id.tvSafeUrl)
        val tvScore     = findViewById<TextView>(R.id.tvSafeScore)
        val llReasons   = findViewById<LinearLayout>(R.id.llSafeReasons)
        val btnBack     = findViewById<Button>(R.id.btnSafeBack)
        val btnContinue = findViewById<Button>(R.id.btnSafeContinue)
        val topBar      = findViewById<android.view.View>(R.id.viewSafeTopBar)

        val isFraud = result.score >= 55

        if (isFraud) {
            topBar.setBackgroundColor(Color.parseColor("#DC2626"))
            tvIcon.text = "🚨"
            tvTitle.text = "LINK BLOQUEADO"
            tvTitle.setTextColor(Color.parseColor("#DC2626"))
            tvSubtitle.text = "Este link foi identificado como provável golpe ou phishing. Não recomendamos acessar."
            btnContinue.text = "⚠ Abrir assim mesmo (risco)"
            btnContinue.setTextColor(Color.parseColor("#DC2626"))
        } else {
            topBar.setBackgroundColor(Color.parseColor("#F59E0B"))
            tvIcon.text = "⚠️"
            tvTitle.text = "LINK SUSPEITO"
            tvTitle.setTextColor(Color.parseColor("#F59E0B"))
            tvSubtitle.text = "Este link possui características suspeitas. Verifique antes de continuar."
            btnContinue.text = "Abrir assim mesmo"
            btnContinue.setTextColor(Color.parseColor("#9CA3AF"))
        }

        tvUrl.text = url.take(80) + if (url.length > 80) "..." else ""
        tvScore.text = "Risco: ${result.score}%"

        result.reasons.forEach { reason ->
            val tv = TextView(this).apply {
                text = "• $reason"
                textSize = 12f
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(0, 6, 0, 6)
            }
            llReasons.addView(tv)
        }

        btnBack.setOnClickListener { finish() }

        btnContinue.setOnClickListener {
            openInBrowser(url)
            finish()
        }
    }

    private fun openInBrowser(url: String) {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                // Ensure we don't loop back to ourselves
                `package` = getDefaultBrowserPackage()
            }
            startActivity(browserIntent)
        } catch (e: Exception) {
            // Fallback without explicit package
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            startActivity(fallback)
        }
    }

    private fun getDefaultBrowserPackage(): String? {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://google.com")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val resolveInfo = packageManager.resolveActivity(intent, 0)
        val pkg = resolveInfo?.activityInfo?.packageName
        // Don't return ourselves
        return if (pkg == packageName) null else pkg
    }
}
