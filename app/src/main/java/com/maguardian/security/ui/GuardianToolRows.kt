package com.maguardian.security.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import com.maguardian.security.R

/** Decorations only; the original native buttons, switches and listeners stay intact. */
internal object GuardianToolRows {
    fun bind(activity: AppCompatActivity) {
        val icons = mapOf(
            R.id.btnLinkChecker to R.drawable.guardian_ic_link,
            R.id.btnAppCache to R.drawable.guardian_ic_database,
            R.id.switchBlockTelemarketing to R.drawable.guardian_ic_phone,
            R.id.switchCallAlerts to R.drawable.guardian_ic_phone_alert,
            R.id.btnReportNumber to R.drawable.guardian_ic_block,
            R.id.btnToolsPermissions to R.drawable.guardian_ic_shield,
            R.id.btnToolsNotifications to R.drawable.guardian_ic_bell,
            R.id.btnUpdateCatalog to R.drawable.guardian_ic_refresh,
            R.id.btnScanHistory to R.drawable.guardian_ic_history,
            R.id.btnToolsLegal to R.drawable.guardian_ic_document
        )
        val switches = setOf(R.id.switchBlockTelemarketing, R.id.switchCallAlerts, R.id.btnToolsNotifications)
        val density = activity.resources.displayMetrics.density
        val accent = GuardianAppearance.color(activity, R.color.guardian_red)
        val muted = GuardianAppearance.color(activity, R.color.guardian_muted)
        icons.forEach { (id, resource) ->
            val view = activity.findViewById<TextView>(id) ?: return@forEach
            if (view.tag != "guardian_tools_row") return@forEach
            val icon = AppCompatResources.getDrawable(activity, resource)!!.mutate()
            DrawableCompat.setTint(icon, accent)
            val left = RowIcon(icon, muted, density).apply { setBounds(0, 0, (46 * density).toInt(), (32 * density).toInt()) }
            val right = if (id in switches) null else Arrow(GuardianAppearance.color(activity, R.color.guardian_ink), density)
                .apply { setBounds(0, 0, (14 * density).toInt(), (20 * density).toInt()) }
            view.setCompoundDrawables(left, null, right, null)
            view.compoundDrawablePadding = (8 * density).toInt()
            val text = view.text.toString()
            val split = text.indexOf('\n')
            if (split >= 0) view.text = SpannableString(text).apply {
                setSpan(RelativeSizeSpan(14f / 16f), split + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(muted), split + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
    }

    private class RowIcon(val icon: Drawable, color: Int, val density: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; alpha = 90; strokeWidth = density }
        override fun draw(canvas: Canvas) {
            icon.setBounds(bounds.left, bounds.top + (2 * density).toInt(),
                bounds.left + (28 * density).toInt(), bounds.top + (30 * density).toInt())
            icon.draw(canvas)
            canvas.drawLine(bounds.right - density, bounds.top + 2 * density,
                bounds.right - density, bounds.bottom - 2 * density, paint)
        }
        override fun setAlpha(alpha: Int) { icon.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { icon.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private class Arrow(color: Int, val density: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; strokeWidth = 1.8f * density; strokeCap = Paint.Cap.ROUND
        }
        override fun draw(canvas: Canvas) {
            val x = bounds.left + 4 * density
            val y = bounds.top + 10 * density
            canvas.drawLine(x, y - 5 * density, x + 5 * density, y, paint)
            canvas.drawLine(x + 5 * density, y, x, y + 5 * density, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
