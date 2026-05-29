package com.maguardian.security.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.maguardian.security.util.CommunityBlocksApi
import com.maguardian.security.util.PrefsHelper

/**
 * Atividade transparente lançada via USE_FULL_SCREEN_INTENT sobre a tela de chamada.
 * Aparece automaticamente sem precisar puxar a barra ou ter SYSTEM_ALERT_WINDOW.
 * FLAG_NOT_TOUCH_MODAL garante que toques nos botões de atender/rejeitar abaixo do card passem
 * para a tela de chamada do sistema.
 */
class CallAlertActivity : Activity() {

    companion object {
        const val EXTRA_NUMBER  = "extra_number"
        const val EXTRA_SCORE   = "extra_score"
        const val EXTRA_LABEL   = "extra_label"
        const val EXTRA_EMOJI   = "extra_emoji"
        const val EXTRA_REASONS = "extra_reasons"
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Aparecer sobre tela de bloqueio / chamada
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        // Fundo transparente — toques na área vazia passam para tela de chamada
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        )

        val number  = intent.getStringExtra(EXTRA_NUMBER) ?: ""
        val score   = intent.getIntExtra(EXTRA_SCORE, 0)
        val label   = intent.getStringExtra(EXTRA_LABEL) ?: "Ligação verificada"
        val emoji   = intent.getStringExtra(EXTRA_EMOJI) ?: "✅"
        @Suppress("UNCHECKED_CAST")
        val reasons = intent.getStringArrayListExtra(EXTRA_REASONS) ?: arrayListOf()

        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        val bgColor     = when { score >= 70 -> 0xEE1A0000.toInt(); score >= 45 -> 0xEE1A0D00.toInt(); score >= 25 -> 0xEE0D0D1A.toInt(); else -> 0xEE001A05.toInt() }
        val borderColor = when { score >= 70 -> 0xFFDC2626.toInt(); score >= 45 -> 0xFFF59E0B.toInt(); score >= 25 -> 0xFF6B7280.toInt(); else -> 0xFF22C55E.toInt() }
        val textColor   = when { score >= 70 -> 0xFFFF6B6B.toInt(); score >= 45 -> 0xFFFFB800.toInt(); score >= 25 -> 0xFFD1D5DB.toInt(); else -> 0xFF4ADE80.toInt() }

        // ── Card de análise ─────────────────────────────────────────────────────
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(bgColor)
                setStroke(dp(2), borderColor)
            }
            elevation = dp(8).toFloat()
        }

        // Linha título
        val rowTitle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvEmoji = TextView(this).apply {
            text = emoji
            textSize = 22f
            setPadding(0, 0, dp(8), 0)
        }

        val tvLabel = TextView(this).apply {
            text = label
            textSize = 17f
            setTextColor(textColor)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvClose = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, 0, 0)
            setOnClickListener { finish() }
        }

        rowTitle.addView(tvEmoji)
        rowTitle.addView(tvLabel)
        rowTitle.addView(tvClose)
        card.addView(rowTitle)

        // Número
        val tvNumber = TextView(this).apply {
            text = if (number.isBlank()) "Número oculto" else number
            textSize = 13f
            setTextColor(0xFFD1D5DB.toInt())
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(tvNumber)

        // Motivos (score >= 25)
        if (reasons.isNotEmpty() && score >= 25) {
            val tvReasons = TextView(this).apply {
                text = reasons.take(3).joinToString("\n") { "• $it" }
                textSize = 11f
                setTextColor(0xFF9CA3AF.toInt())
                setPadding(0, dp(6), 0, 0)
            }
            card.addView(tvReasons)
        }

        // Botões
        val rowBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }

        val btnDismiss = Button(this).apply {
            text = "Fechar"
            setTextColor(0xFFD1D5DB.toInt())
            background = null
            setOnClickListener { finish() }
        }
        rowBtns.addView(btnDismiss)

        if (number.isNotBlank()) {
            val btnBlock = Button(this).apply {
                text = "🚫 Bloquear"
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(0xFFDC2626.toInt())
                }
                setPadding(dp(14), dp(6), dp(14), dp(6))
                setOnClickListener {
                    PrefsHelper.blockNumber(this@CallAlertActivity, number)
                    CommunityBlocksApi.reportBlock(number)
                    finish()
                }
            }
            rowBtns.addView(btnBlock)
        }
        card.addView(rowBtns)

        // ── Container raiz: card no topo, abaixo é transparente ────────────────
        val container = FrameLayout(this)
        container.setBackgroundColor(Color.TRANSPARENT)

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
            topMargin = dp(24)
            leftMargin = dp(12)
            rightMargin = dp(12)
        }
        container.addView(card, lp)

        // Área abaixo do card — clicável para fechar ao toque fora do card
        val touchInterceptor = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }
        container.addView(touchInterceptor, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(container)

        // Auto-dismiss
        val timeout = if (score < 25) 15_000L else 60_000L
        handler.postDelayed({ if (!isFinishing) finish() }, timeout)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
