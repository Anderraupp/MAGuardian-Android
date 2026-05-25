package com.maguardian.security.util

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Mostra um card flutuante (TYPE_APPLICATION_OVERLAY) sobre qualquer tela,
 * incluindo a tela de chamada recebida, sem precisar puxar a barra de notificações.
 *
 * Requer permissão SYSTEM_ALERT_WINDOW (Settings.canDrawOverlays).
 */
object CallOverlayManager {

    private var overlayView: LinearLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null

    fun show(context: Context, number: String, result: PhoneAnalyzer.Result) {
        if (!Settings.canDrawOverlays(context)) return

        handler.post {
            dismiss(context) // remove overlay anterior se existir

            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            // ── Cores por nível de risco ─────────────────────────────────────────
            val (bgColor, borderColor, textColor) = when {
                result.score >= 70 -> Triple(0xFF1A0000.toInt(), 0xFFDC2626.toInt(), 0xFFFF6B6B.toInt())
                result.score >= 45 -> Triple(0xFF1A0D00.toInt(), 0xFFF59E0B.toInt(), 0xFFFFB800.toInt())
                result.score >= 25 -> Triple(0xFF0D0D1A.toInt(), 0xFF6B7280.toInt(), 0xFFD1D5DB.toInt())
                else               -> Triple(0xFF001A05.toInt(), 0xFF22C55E.toInt(), 0xFF4ADE80.toInt())
            }

            // ── Raiz do overlay ───────────────────────────────────────────────────
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(context, 16).toFloat()
                    setColor(bgColor)
                    setStroke(dp(context, 2), borderColor)
                }
                elevation = dp(context, 8).toFloat()
            }

            // ── Linha do topo: emoji + label + botão fechar ───────────────────────
            val rowTop = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val tvEmoji = TextView(context).apply {
                text = result.emoji
                textSize = 24f
                setPadding(0, 0, dp(context, 8), 0)
            }

            val tvLabel = TextView(context).apply {
                text = result.label
                textSize = 16f
                setTextColor(textColor)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnClose = ImageButton(context).apply {
                setImageDrawable(context.getDrawable(android.R.drawable.ic_menu_close_clear_cancel))
                setColorFilter(Color.WHITE)
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
                setOnClickListener { dismiss(context) }
            }

            rowTop.addView(tvEmoji)
            rowTop.addView(tvLabel)
            rowTop.addView(btnClose)
            root.addView(rowTop)

            // ── Número ────────────────────────────────────────────────────────────
            val tvNumber = TextView(context).apply {
                text = if (number.isBlank()) "Número oculto" else number
                textSize = 13f
                setTextColor(0xFFD1D5DB.toInt())
                setPadding(0, dp(context, 4), 0, 0)
            }
            root.addView(tvNumber)

            // ── Motivos (apenas para suspeitos) ───────────────────────────────────
            if (result.score >= 25 && result.reasons.isNotEmpty()) {
                val tvReasons = TextView(context).apply {
                    text = result.reasons.joinToString("\n") { "• $it" }
                    textSize = 11f
                    setTextColor(0xFF9CA3AF.toInt())
                    setPadding(0, dp(context, 6), 0, 0)
                }
                root.addView(tvReasons)
            }

            // ── Parâmetros da janela ─────────────────────────────────────────────
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.FILL_HORIZONTAL
                x = dp(context, 12)
                y = dp(context, 48)
                width = WindowManager.LayoutParams.MATCH_PARENT - dp(context, 24)
            }

            try {
                wm.addView(root, params)
                overlayView = root
            } catch (e: Exception) {
                android.util.Log.e("CallOverlay", "Erro ao exibir overlay: ${e.message}")
                return@post
            }

            // Auto-dismiss: 20s para ligação segura, 60s para suspeita/golpe
            val timeout = if (result.score < 25) 20_000L else 60_000L
            val runnable = Runnable { dismiss(context) }
            dismissRunnable = runnable
            handler.postDelayed(runnable, timeout)
        }
    }

    fun dismiss(context: Context) {
        handler.post {
            dismissRunnable?.let { handler.removeCallbacks(it) }
            dismissRunnable = null
            overlayView?.let { view ->
                try {
                    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    wm.removeView(view)
                } catch (_: Exception) {}
                overlayView = null
            }
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
