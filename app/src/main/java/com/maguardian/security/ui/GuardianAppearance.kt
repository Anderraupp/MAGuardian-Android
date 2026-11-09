package com.maguardian.security.ui

import android.content.Context
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.maguardian.security.R

/** The choice is local to this installation, not a permission or a protection setting. */
internal object GuardianAppearance {
    private const val PREFS = "guardian_appearance"
    private const val KEY_MODE = "night_mode"

    private fun mode(context: Context): Int {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MODE, AppCompatDelegate.MODE_NIGHT_NO)
        return if (saved == AppCompatDelegate.MODE_NIGHT_YES) saved else AppCompatDelegate.MODE_NIGHT_NO
    }

    fun apply(activity: AppCompatActivity) {
        // Per-activity delegates avoid recoloring the existing phone-call overlay.
        activity.delegate.localNightMode = mode(activity)
        activity.setTheme(R.style.Theme_MAGuardian_Preview)
    }

    fun isLight(context: Context) = context.resources.getBoolean(R.bool.guardian_light_bars)

    fun bind(activity: AppCompatActivity, canChange: () -> Boolean = { true }) {
        activity.findViewById<ImageButton>(R.id.btnAppearance).setOnClickListener {
            if (!canChange()) {
                Toast.makeText(activity, "Aguarde a análise terminar para mudar o tema.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val current = mode(activity)
            AlertDialog.Builder(activity)
                .setTitle("Aparência do aplicativo")
                .setSingleChoiceItems(arrayOf("Claro", "Escuro"), if (current == AppCompatDelegate.MODE_NIGHT_YES) 1 else 0) { dialog, which ->
                    val selected = if (which == 1) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                    activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MODE, selected).apply()
                    dialog.dismiss()
                    if (selected != current) activity.delegate.localNightMode = selected
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }
}
