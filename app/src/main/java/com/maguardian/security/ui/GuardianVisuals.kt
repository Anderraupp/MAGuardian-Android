package com.maguardian.security.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import com.maguardian.security.R

/** Presentation only: assign bounded, theme-aware icons after native inflation. */
internal object GuardianVisuals {
    fun bind(activity: AppCompatActivity) {
        val actions = mapOf(
            R.id.btnScan to R.drawable.guardian_ic_search,
            R.id.btnPaste to R.drawable.guardian_ic_clipboard,
            R.id.etUrl to R.drawable.guardian_ic_link,
            R.id.btnCheck to R.drawable.guardian_ic_search,
            R.id.btnLinkChecker to R.drawable.guardian_ic_link,
            R.id.btnAppCache to R.drawable.guardian_ic_cache,
            R.id.btnReportNumber to R.drawable.guardian_ic_phone,
            R.id.btnToolsPermissions to R.drawable.guardian_ic_shield,
            R.id.btnToolsNotifications to R.drawable.guardian_ic_bell,
            R.id.btnUpdateCatalog to R.drawable.guardian_ic_cache,
            R.id.btnScanHistory to R.drawable.guardian_ic_history,
            R.id.btnAppearance to R.drawable.guardian_ic_designer,
            R.id.btnToolsPrivacy to R.drawable.guardian_ic_shield
        )
        val benefits = mapOf(
            "benefit_search" to R.drawable.guardian_ic_search, "benefit_shield" to R.drawable.guardian_ic_shield,
            "benefit_cache" to R.drawable.guardian_ic_cache, "benefit_phone" to R.drawable.guardian_ic_phone,
            "benefit_link" to R.drawable.guardian_ic_link, "benefit_history" to R.drawable.guardian_ic_history,
            "benefit_headset" to R.drawable.guardian_ic_headset
        )
        fun visit(view: View) {
            if (view is TextView) {
                val resource = actions[view.id] ?: benefits[view.tag as? String]
                if (resource != null) {
                    val drawable = AppCompatResources.getDrawable(activity, resource)!!.mutate()
                    val color = if (view.id == R.id.btnScan || view.id == R.id.btnCheck)
                        R.color.guardian_button_text else R.color.guardian_red
                    DrawableCompat.setTint(drawable, GuardianAppearance.color(activity, color))
                    val size = (22 * activity.resources.displayMetrics.density).toInt()
                    drawable.setBounds(0, 0, size, size)
                    view.setCompoundDrawables(drawable, null, null, null)
                    view.compoundDrawablePadding = (10 * activity.resources.displayMetrics.density).toInt()
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(activity.findViewById(android.R.id.content))
        GuardianToolRows.bind(activity)
    }
}
