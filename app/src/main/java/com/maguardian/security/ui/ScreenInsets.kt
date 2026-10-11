package com.maguardian.security.ui

import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach

/** Insets are always applied to the original padding, never accumulated. */
internal data class ContentPadding(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun withInsets(left: Int, top: Int, right: Int, bottom: Int) =
        ContentPadding(this.left + left, this.top + top, this.right + right, this.bottom + bottom)
}

/** All full-screen dark screens share one edge-to-edge/insets policy. */
internal object ScreenInsets {
    fun configure(window: Window) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Bar transparency is supplied by the theme, not deprecated color APIs.
    }

    fun apply(activity: AppCompatActivity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) ?: return
        val original = ContentPadding(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        WindowCompat.getInsetsController(activity.window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view: View, insets ->
            // Union selects the maximum on each edge, not bars + keyboard added twice.
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            val padding = original.withInsets(safe.left, safe.top, safe.right, safe.bottom)
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            insets
        }
        root.doOnAttach { ViewCompat.requestApplyInsets(it) }
    }
}
