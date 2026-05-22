package com.maguardian.security.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.maguardian.security.R
import com.maguardian.security.util.LinkChecker
import com.google.android.material.textfield.TextInputEditText

class LinkCheckerActivity : AppCompatActivity() {

    private lateinit var etUrl: android.widget.EditText
    private lateinit var btnCheck: Button
    private lateinit var btnPaste: Button
    private lateinit var btnBack: TextView
    private lateinit var cardResult: LinearLayout
    private lateinit var tvVerdictIcon: TextView
    private lateinit var tvVerdict: TextView
    private lateinit var tvScore: TextView
    private lateinit var viewScoreBar: android.view.View
    private lateinit var tvReasonsLabel: TextView
    private lateinit var llReasons: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        setContentView(R.layout.activity_link_checker)

        etUrl = findViewById(R.id.etUrl)
        btnCheck = findViewById(R.id.btnCheck)
        btnPaste = findViewById(R.id.btnPaste)
        btnBack = findViewById(R.id.btnBack)
        cardResult = findViewById(R.id.cardResult)
        tvVerdictIcon = findViewById(R.id.tvVerdictIcon)
        tvVerdict = findViewById(R.id.tvVerdict)
        tvScore = findViewById(R.id.tvScore)
        viewScoreBar = findViewById(R.id.viewScoreBar)
        tvReasonsLabel = findViewById(R.id.tvReasonsLabel)
        llReasons = findViewById(R.id.llReasons)

        btnBack.setOnClickListener { finish() }

        btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                etUrl.setText(clip.getItemAt(0).coerceToText(this))
            }
        }

        btnCheck.setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (url.isEmpty()) {
                etUrl.error = "Cole ou digite um link"
                return@setOnClickListener
            }
            showResult(LinkChecker.check(url))
        }

        val urlFromIntent = intent.getStringExtra("url")
        if (!urlFromIntent.isNullOrEmpty()) {
            etUrl.setText(urlFromIntent)
            showResult(LinkChecker.check(urlFromIntent))
        }
    }

    private fun showResult(result: LinkChecker.Result) {
        cardResult.visibility = android.view.View.VISIBLE

        val (icon, color) = when (result.verdict) {
            "FRAUDULENTO" -> "🚨" to "#DC2626"
            "SUSPEITO" -> "⚠️" to "#F59E0B"
            else -> "✅" to "#22C55E"
        }

        tvVerdictIcon.text = icon
        tvVerdict.text = result.verdict
        tvVerdict.setTextColor(Color.parseColor(color))
        tvScore.text = "Risco: ${result.score}%"

        val params = viewScoreBar.layoutParams as FrameLayout.LayoutParams
        val screenWidth = resources.displayMetrics.widthPixels
        val barMaxWidth = screenWidth - (32 * resources.displayMetrics.density).toInt()
        params.width = ((barMaxWidth * result.score) / 100).coerceAtLeast(4)
        viewScoreBar.layoutParams = params
        viewScoreBar.setBackgroundColor(Color.parseColor(color))

        llReasons.removeAllViews()
        if (result.reasons.isNotEmpty()) {
            tvReasonsLabel.visibility = android.view.View.VISIBLE
            result.reasons.forEach { reason ->
                val tv = TextView(this).apply {
                    text = "⚡ $reason"
                    textSize = 12f
                    setTextColor(Color.parseColor("#9CA3AF"))
                    setPadding(
                        (10 * resources.displayMetrics.density).toInt(),
                        (8 * resources.displayMetrics.density).toInt(),
                        (10 * resources.displayMetrics.density).toInt(),
                        (8 * resources.displayMetrics.density).toInt()
                    )
                    setBackgroundColor(Color.parseColor("#1A1010"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (6 * resources.displayMetrics.density).toInt()
                    }
                }
                llReasons.addView(tv)
            }
        } else {
            tvReasonsLabel.visibility = android.view.View.GONE
        }
    }
}
