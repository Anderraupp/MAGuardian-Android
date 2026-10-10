package com.maguardian.security.data

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.maguardian.security.R
import com.maguardian.security.ui.MainActivity
import com.maguardian.security.util.PrefsHelper
import org.hamcrest.Matchers.containsString
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Actual button, status rendering and offline license dialog on the isolated APK. */
class ThreatCatalogUiTest {
    @Test fun manualButtonRecoversAndOfflineLicensesRemainAccessible() {
        val ctx = CatalogTestEnvironment.target()
        val offline = InstrumentationRegistry.getArguments().getString("network") == "offline"
        CatalogTestEnvironment.resetMemory()
        PrefsHelper.setProtectionEnabled(ctx, false)
        PrefsHelper.setNotifPermissionAsked(ctx)
        PrefsHelper.setLastTrialPopup(ctx, System.currentTimeMillis())
        PrefsHelper.setCatalogAttempt(ctx, System.currentTimeMillis())
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.btnUpdateCatalog)).perform(scrollTo(), click())
            val deadline = System.currentTimeMillis() + 60000
            while (System.currentTimeMillis() < deadline) {
                var enabled = false
                it.onActivity { activity ->
                    enabled = activity.findViewById<android.widget.Button>(R.id.btnUpdateCatalog).isEnabled
                }
                if (enabled) break
                Thread.sleep(200)
            }
            onView(withId(R.id.btnUpdateCatalog)).check(matches(isEnabled()))
            onView(withId(R.id.tvCatalogStatus)).check(matches(withText(containsString(
                if (offline) "Falha na atualização" else "Catálogo v"
            ))))
            onView(withId(R.id.btnCatalogSources)).perform(scrollTo(), click())
            onView(withText("Fontes e licenças dos indicadores")).check(matches(
                androidx.test.espresso.matcher.ViewMatchers.isDisplayed()
            ))
            onView(withText("Fechar")).perform(click())
            if (!offline) {
                assertTrue(PrefsHelper.getCatalogSuccess(ctx) > 0)
            }
        }
        CatalogTestEnvironment.resetMemory()
        if (!offline) File(ctx.noBackupFilesDir, "threat-catalog.json").delete()
    }
}
