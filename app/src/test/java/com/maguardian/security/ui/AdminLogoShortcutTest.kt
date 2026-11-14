package com.maguardian.security.ui

import android.app.Application
import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import com.maguardian.security.util.AppConfig

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
                    repeat(4) { shortcut.performClick() }
                    assertEquals(1, openings)
                    shortcut.performClick()
                    assertEquals(2, openings)
                    button.performClick()
                    assertEquals(3, openings)
                } finally {
                    controller.stop().destroy()
                }
            }
        }
    }

    @Test fun logoOpensRealLoginDialogAgainAfterCancelAndKeepsAuthorizationRequired() {
        val ctx = RuntimeEnvironment.getApplication()
        for (palette in listOf("original", "petroleo", "gold")) {
            ctx.getSharedPreferences("catalog_admin_ui", 0).edit().clear().commit()
            ctx.getSharedPreferences("guardian_appearance", 0).edit()
                .putString("palette", palette).commit()
            val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
            try {
                val activity = controller.get()
                val logo = activity.findViewById<View>(R.id.ivGuardianLogo)
                repeat(5) { logo.performClick() }
                val first = ShadowAlertDialog.getLatestAlertDialog()
                assertTrue(first.isShowing)
                assertEquals("Administração da base", shadowOf(first).title.toString())
                first.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(first.isShowing)
                repeat(4) { logo.performClick() }
                assertFalse(first.isShowing)
                logo.performClick()
                val second = ShadowAlertDialog.getLatestAlertDialog()
                assertNotSame(first, second)
                assertTrue(second.isShowing)
                assertFalse(CatalogAdmin.isEnabled(activity))
                second.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                val browser = shadowOf(activity).nextStartedActivity
                assertEquals(Intent.ACTION_VIEW, browser.action)
                assertEquals("${AppConfig.ADMIN_BASE_URL}/admin", browser.data.toString().substringBefore("#"))
                assertTrue(browser.data!!.fragment!!.startsWith("device="))
                assertFalse(CatalogAdmin.isEnabled(activity))
            } finally {
                controller.stop().destroy()
            }
        }
    }
}
