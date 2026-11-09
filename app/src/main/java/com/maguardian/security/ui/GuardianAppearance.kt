package com.maguardian.security.ui

import android.content.Context
import android.util.TypedValue
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.maguardian.security.R
import java.util.WeakHashMap

/** The choice is local to this installation, not a permission or a protection setting. */
internal object GuardianAppearance {
    private const val PREFS = "guardian_appearance"
    private const val KEY_PALETTE = "palette"
    private val applied = WeakHashMap<AppCompatActivity, GuardianPaletteChoice>()

    private fun selected(context: Context) = GuardianPaletteChoice.fromSaved(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PALETTE, null)
    )

    fun apply(activity: AppCompatActivity) {
        // Per-activity delegates avoid recoloring the existing phone-call overlay.
        val palette = selected(activity)
        applied[activity] = palette
        activity.delegate.localNightMode = if (palette.light)
            AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        activity.setTheme(when (palette) {
            GuardianPaletteChoice.ORIGINAL -> R.style.Theme_MAGuardian_Palette_Original
            GuardianPaletteChoice.PETROLEO -> R.style.Theme_MAGuardian_Palette_Petroleo
            GuardianPaletteChoice.GOLD -> R.style.Theme_MAGuardian_Palette_Gold
        })
    }

    fun isLight(context: Context) = selected(context).light

    /** A screen underneath the picker may resume with an outdated inflated theme. */
    fun refreshIfChanged(activity: AppCompatActivity): Boolean {
        if (applied[activity] == selected(activity)) return false
        activity.recreate()
        return true
    }

    /** Kotlin-created rows and results must use the same attributes as XML views. */
    fun color(context: Context, resource: Int): Int {
        val attribute = when (resource) {
            R.color.guardian_paper -> R.attr.guardian_paper
            R.color.guardian_card -> R.attr.guardian_card
            R.color.guardian_ink -> R.attr.guardian_ink
            R.color.guardian_muted -> R.attr.guardian_muted
            R.color.guardian_line -> R.attr.guardian_line
            R.color.guardian_red -> R.attr.guardian_red
            R.color.guardian_red_deep -> R.attr.guardian_red_deep
            R.color.guardian_wash -> R.attr.guardian_wash
            R.color.guardian_success -> R.attr.guardian_success
            R.color.guardian_button_bg -> R.attr.guardian_button_bg
            R.color.guardian_button_text -> R.attr.guardian_button_text
            R.color.guardian_danger -> R.attr.guardian_danger
            R.color.guardian_warning -> R.attr.guardian_warning
            else -> error("Unknown Guardian palette color")
        }
        val value = TypedValue()
        check(context.theme.resolveAttribute(attribute, value, true)) {
            "Guardian palette attribute is missing"
        }
        return if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
    }

    fun showDesigner(activity: AppCompatActivity, canChange: () -> Boolean = { true }) {
        if (!canChange()) {
            Toast.makeText(activity, "Aguarde a análise terminar para mudar as cores.", Toast.LENGTH_LONG).show()
            return
        }
        val current = selected(activity)
        val options = GuardianPaletteChoice.entries
        AlertDialog.Builder(activity)
            .setTitle("Designer")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), options.indexOf(current)) { dialog, which ->
                val chosen = options[which]
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_PALETTE, chosen.id).apply()
                dialog.dismiss()
                if (chosen != current) activity.recreate()
            }
            .setNegativeButton("Voltar", null)
            .show()
    }
}
