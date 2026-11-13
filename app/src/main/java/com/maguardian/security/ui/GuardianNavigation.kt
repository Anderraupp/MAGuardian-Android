package com.maguardian.security.ui

import android.content.Intent
import android.view.View
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.maguardian.security.R

internal object GuardianNavigation {
    const val TOOLS = "guardian_open_tools"
    fun bind(activity: AppCompatActivity, canChangeAppearance: () -> Boolean = { true }, subscribe: (() -> Unit)? = null) {
        val nav = activity.findViewById<BottomNavigationView>(R.id.guardianBottomNav)
        nav.isItemActiveIndicatorEnabled = true
        nav.itemActiveIndicatorColor = android.content.res.ColorStateList.valueOf(GuardianAppearance.color(activity, R.color.guardian_wash))
        nav.itemActiveIndicatorShapeAppearance = com.google.android.material.shape.ShapeAppearanceModel.builder()
            .setAllCornerSizes(14 * activity.resources.displayMetrics.density).build()
        // Five destinations remain readable with the system font enlarged.
        fun wrapLabels(view: View) {
            if (view is android.widget.TextView) {
                view.setSingleLine(false)
                view.maxLines = 2
                view.ellipsize = null
                view.gravity = android.view.Gravity.CENTER
            }
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) wrapLabels(view.getChildAt(i))
        }
        wrapLabels(nav)
        nav.minimumHeight = ((68 + 24 * (activity.resources.configuration.fontScale - 1).coerceAtLeast(0f)) *
            activity.resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(nav) { _, insets -> insets }
        nav.selectedItemId = when (activity) {
            is SubscriptionActivity -> R.id.navGuardianSubscription
            is SupportActivity -> R.id.navGuardianContact
            is LinkCheckerActivity, is AppCacheActivity, is ScanHistoryActivity -> R.id.navGuardianDesigner
            else -> R.id.navGuardianHome
        }
        fun navigate(id: Int) {
            when (id) {
                R.id.navGuardianHome, R.id.navGuardianDesigner -> {
                    val tools = id == R.id.navGuardianDesigner
                    if (activity is MainActivity) selectMain(activity, tools)
                    else activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra(TOOLS, tools)
                    })
                }
                R.id.navGuardianAbout -> GuardianExternal.web(activity, GuardianExternal.ABOUT)
                R.id.navGuardianContact -> if (activity !is SupportActivity)
                    activity.startActivity(Intent(activity, SupportActivity::class.java))
                R.id.navGuardianSubscription -> if (activity !is SubscriptionActivity) {
                    if (subscribe != null) subscribe()
                    else activity.startActivity(Intent(activity, SubscriptionActivity::class.java))
                }
            }
        }
        nav.setOnItemSelectedListener { navigate(it.itemId); false }
        nav.setOnItemReselectedListener { navigate(it.itemId) }
    }
    fun selectMain(activity: MainActivity, tools: Boolean) {
        activity.findViewById<View>(R.id.guardianHomeContent).visibility = if (tools) View.GONE else View.VISIBLE
        activity.findViewById<View>(R.id.guardianToolsContent).visibility = if (tools) View.VISIBLE else View.GONE
        val nav = activity.findViewById<BottomNavigationView>(R.id.guardianBottomNav)
        nav.menu.findItem(if (tools) R.id.navGuardianDesigner else R.id.navGuardianHome).isChecked = true
        activity.findViewById<ScrollView>(R.id.guardianScrollContent).scrollTo(0, 0)
    }
}
