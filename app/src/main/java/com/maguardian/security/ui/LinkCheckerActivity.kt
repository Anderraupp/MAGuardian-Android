package com.maguardian.security.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.maguardian.security.R
import com.maguardian.security.util.LinkChecker

class LinkCheckerActivity : AppCompatActivity() {

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        setContentView(R.layout.activity_link_checker)

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
            if (url.isEmpty()) { etUrl.error = "Cole ou digite um link"; return@setOnClickListener }
            showResult(LinkChecker.check(url))
        }
    }

    private fun showResult(result: LinkChecker.Result) {
        cardResult.visibility = View.VISIBLE

        val (icon, colorHex) = when (result.verdict) {
            "FRAUDULENTO" -> "🚨" to "#DC2626"
            "SUSPEITO"    -> "⚠️" to "#F59E0B"
            else          -> "✅" to "#22C55E"
        }

        tvVerdictIcon.text = icon
        tvVerdict.text = result.verdict
        tvVerdict.setTextColor(Color.parseColor(colorHex))
        tvScore.text = "Risco: ${result.score}%"

        val params = viewScoreBar.layoutParams as FrameLayout.LayoutParams
        val barMaxPx = (resources.displayMetrics.widthPixels
                - (64 * resources.displayMetrics.density).toInt())
        params.width = ((barMaxPx * result.score) / 100).coerceAtLeast(4)
        viewScoreBar.layoutParams = params
        viewScoreBar.setBackgroundColor(Color.parseColor(colorHex))

        llReasons.removeAllViews()
        if (result.reasons.isNotEmpty()) {
            tvReasonsLabel.visibility = View.VISIBLE
            val dp6  = (6  * resources.displayMetrics.density).toInt()
            val dp10 = (10 * resources.displayMetrics.density).toInt()
            result.reasons.forEach { reason ->
                val tv = TextView(this).apply {
                    text = "⚡ $reason"
                    textSize = 12f
                    setTextColor(Color.parseColor("#9CA3AF"))
                    setPadding(dp10, dp6, dp10, dp6)
                    setBackgroundColor(Color.parseColor("#1A1010"))
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
