package com.maguardian.security.data

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.*
import android.graphics.Rect
import android.view.View
import android.widget.ScrollView
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
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
    // API 36 may apply the ScrollView's requested rectangle on the next frame.
    // Wait for actual visibility; never bypass the real button's click constraints.
    private fun settledScroll(): ViewAction = object : ViewAction {
        override fun getDescription() = "scroll and wait until the button is actually visible"
        override fun getConstraints(): Matcher<View> = allOf(
            withEffectiveVisibility(Visibility.VISIBLE),
            isDescendantOfA(isAssignableFrom(ScrollView::class.java))
        )
        override fun perform(controller: UiController, view: View) {
            val rect = Rect()
            repeat(20) {
                view.getDrawingRect(rect)
                view.requestRectangleOnScreen(rect, true)
                controller.loopMainThreadForAtLeast(100)
                if (isDisplayingAtLeast(90).matches(view)) return
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(instrumentation.targetContext.cacheDir, "catalog-ui-failure.png")
                    .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            throw AssertionError("Catalog button did not become visible after scrolling")
        }
    }

    @Test fun manualButtonRecoversAndOfflineLicensesRemainAccessible() {
        val ctx = CatalogTestEnvironment.target()
        val offline = InstrumentationRegistry.getArguments().getString("network") == "offline"
        CatalogTestEnvironment.resetMemory()
        PrefsHelper.setProtectionEnabled(ctx, false)
        PrefsHelper.setNotifPermissionAsked(ctx)
        PrefsHelper.setLastTrialPopup(ctx, System.currentTimeMillis())
        PrefsHelper.setCatalogAttempt(ctx, System.currentTimeMillis())
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.btnUpdateCatalog)).perform(settledScroll(), click())
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
            onView(withId(R.id.btnCatalogSources)).perform(settledScroll(), click())
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
