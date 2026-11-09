package com.maguardian.security.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.maguardian.security.R
import com.maguardian.security.util.LinkChecker
import java.net.URI
import java.net.URISyntaxException

class LinkCheckerActivity : AppCompatActivity() {
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // Register after Android restores the input, so rotation/theme changes
        // keep their saved result but editing a different link never keeps green.
        etUrl.doAfterTextChanged {
            clearResult()
            etUrl.error = null
        }
    }
    override fun onResume() {
        super.onResume()
        GuardianAppearance.refreshIfChanged(this)
    }


    private lateinit var etUrl: android.widget.EditText
    private lateinit var btnCheck: Button
    private lateinit var btnPaste: Button
    private lateinit var btnBack: TextView
    private lateinit var cardResult: LinearLayout
    private lateinit var tvVerdictIcon: TextView
    private lateinit var tvVerdict: TextView
    private lateinit var tvScore: TextView
    private lateinit var viewScoreBar: View
    private lateinit var tvReasonsLabel: TextView
    private lateinit var llReasons: LinearLayout
    private var lastResult: LinkChecker.Result? = null

    override fun onSaveInstanceState(outState: Bundle) {
        lastResult?.let { result ->
            outState.putBundle("guardian_link_result", Bundle().apply {
                putInt("score", result.score)
                putString("verdict", result.verdict)
                putString("category", result.category)
                putStringArrayList("reasons", ArrayList(result.reasons))
            })
        }
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        GuardianAppearance.apply(this)
        super.onCreate(savedInstanceState)
        ScreenInsets.configure(window)
        setContentView(R.layout.activity_link_checker)
        ScreenInsets.apply(this, light = GuardianAppearance.isLight(this))
        GuardianNavigation.bind(this, canChangeAppearance = { btnCheck.isEnabled })

        etUrl         = findViewById(R.id.etUrl)
        btnCheck      = findViewById(R.id.btnCheck)
        btnPaste      = findViewById(R.id.btnPaste)
        btnBack       = findViewById(R.id.btnBack)
        cardResult    = findViewById(R.id.cardResult)
        tvVerdictIcon = findViewById(R.id.tvVerdictIcon)
        tvVerdict     = findViewById(R.id.tvVerdict)
        tvScore       = findViewById(R.id.tvScore)
        viewScoreBar  = findViewById(R.id.viewScoreBar)
        tvReasonsLabel = findViewById(R.id.tvReasonsLabel)
        llReasons     = findViewById(R.id.llReasons)

        btnBack.setOnClickListener { finish() }

        btnPaste.setOnClickListener {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cb.primaryClip
            if (clip != null && clip.itemCount > 0) {
                etUrl.setText(clip.getItemAt(0).coerceToText(this))
            }
        }

        btnCheck.setOnClickListener {
            val url = etUrl.text.toString().trim()
            val normalized = normalizedHttpUrl(url)
            if (normalized == null) {
                clearResult()
                etUrl.error = if (url.isEmpty()) "Cole ou digite um link"
                    else "Endereço inválido ou não suportado. Use um link http:// ou https://."
                return@setOnClickListener
            }
            try {
                showResult(LinkChecker.check(normalized))
            } catch (_: Exception) {
                showAnalysisError()
            }
        }
        savedInstanceState?.getBundle("guardian_link_result")?.let { saved ->
            val verdict = saved.getString("verdict") ?: return@let
            val category = saved.getString("category") ?: return@let
            val reasons = saved.getStringArrayList("reasons") ?: return@let
            showResult(LinkChecker.Result(saved.getInt("score"), verdict, category, reasons))
        }
    }

    private fun normalizedHttpUrl(input: String): String? {
        if (input.isEmpty() || input.any { it.isWhitespace() || it.isISOControl() }) return null
        val protocol = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").find(input)?.value?.dropLast(1)
        if (protocol != null && !protocol.equals("http", true) &&
            !protocol.equals("https", true) && !protocol.contains(".")) return null
        val candidate = when {
            input.startsWith("//") -> "https:$input"
            Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(input) -> input
            else -> "https://$input"
        }
        val parsed = try { URI(candidate) } catch (_: URISyntaxException) { return null }
        if (!parsed.scheme.equals("http", true) && !parsed.scheme.equals("https", true)) return null
        if (parsed.host.isNullOrBlank() || parsed.port < -1 || parsed.port > 65535) return null
        return candidate
    }

    private fun clearResult() {
        lastResult = null
        cardResult.visibility = View.GONE
    }

    private fun showAnalysisError() {
        clearResult()
        etUrl.error = "Não foi possível concluir a verificação. Não considere este link seguro."
    }

    private fun showResult(result: LinkChecker.Result) {
        val verdict = result.verdict.uppercase()
        if (verdict !in setOf("SEGURO", "SUSPEITO", "FRAUDULENTO")) {
            showAnalysisError()
            return
        }
        lastResult = result
        cardResult.visibility = View.VISIBLE

        val (icon, colorResource) = when (verdict) {
            "FRAUDULENTO" -> "!" to R.color.guardian_danger
            "SUSPEITO"    -> "!" to R.color.guardian_warning
            else          -> "✓" to R.color.guardian_success
        }
        val color = GuardianAppearance.color(this, colorResource)

        tvVerdictIcon.text = icon
        tvVerdictIcon.setTextColor(color)
        val verdictLabel = when (verdict) {
            "FRAUDULENTO" -> "Alto risco: não abra este link"
            "SUSPEITO" -> "Suspeito: pode haver risco"
            else -> "Seguro nesta análise"
        }
        tvVerdict.text = "$verdictLabel — ${result.category}"
        tvVerdict.setTextColor(color)
        tvScore.text = "Pontuação de atenção: ${result.score}/100"

        viewScoreBar.post {
            val params = viewScoreBar.layoutParams as FrameLayout.LayoutParams
            val maxWidth = (viewScoreBar.parent as View).width
            params.width = ((maxWidth * result.score.coerceIn(0, 100)) / 100).coerceAtLeast(4)
            viewScoreBar.layoutParams = params
        }
        viewScoreBar.setBackgroundColor(color)

        llReasons.removeAllViews()
        if (result.reasons.isNotEmpty()) {
            tvReasonsLabel.visibility = View.VISIBLE
            val dp6  = (6  * resources.displayMetrics.density).toInt()
            val dp10 = (10 * resources.displayMetrics.density).toInt()
            result.reasons.forEach { reason ->
                val tv = TextView(this).apply {
                    text = "• $reason"
                    textSize = 14f
                    setTextColor(GuardianAppearance.color(this@LinkCheckerActivity, R.color.guardian_muted))
                    setPadding(dp10, dp6, dp10, dp6)
                    setBackgroundColor(GuardianAppearance.color(this@LinkCheckerActivity, R.color.guardian_wash))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp6 }
                }
                llReasons.addView(tv)
            }
        } else {
            tvReasonsLabel.visibility = View.GONE
        }
    }
}
