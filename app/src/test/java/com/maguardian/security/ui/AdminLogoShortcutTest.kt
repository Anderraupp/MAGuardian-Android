package com.maguardian.security.ui

import android.app.Application
import android.view.View
import com.maguardian.security.R
import com.maguardian.security.util.CatalogAdmin
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
class AdminLogoShortcutTest {
    @Test fun fiveTapsOnLogoOrTitleInvokeExistingEntryWithoutGrantingAdmin() {
        val ctx = RuntimeEnvironment.getApplication()
        for (palette in listOf("original", "petroleo", "gold")) {
            for (target in listOf(R.id.ivGuardianLogo, R.id.tvAppTitle)) {
                ctx.getSharedPreferences("catalog_admin_ui", 0).edit().clear().commit()
                ctx.getSharedPreferences("guardian_appearance", 0).edit()
                    .putString("palette", palette).commit()
                val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
                try {
                    val activity = controller.get()
                    val button = activity.findViewById<View>(R.id.btnCatalogAdmin)
                    var openings = 0
                    // Observe dispatch without opening an external browser during this unit test.
                    button.setOnClickListener { openings++ }
                    assertEquals(View.GONE, button.visibility)
                    val shortcut = activity.findViewById<View>(target)
                    repeat(4) { shortcut.performClick() }
                    assertEquals(0, openings)
                    assertEquals(View.GONE, button.visibility)
                    shortcut.performClick()
                    assertEquals(1, openings)
                    assertEquals(View.VISIBLE, button.visibility)
                    assertFalse(CatalogAdmin.isEnabled(activity))
                    button.performClick()
                    assertEquals(2, openings)
                } finally {
                    controller.stop().destroy()
                }
            }
        }
    }
}
