package com.maguardian.security.data

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.*
import android.view.View
import android.view.LayoutInflater
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.widget.LinearLayout
import android.widget.TextView
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
    @Test fun threatCardKeepsDetailsAndActionsVisibleWithAdminAndLargeFonts() {
        val ctx = CatalogTestEnvironment.target()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (widthDp in listOf(280, 360, 640)) {
                for (fontScale in listOf(1f, 2f)) {
                    for (admin in listOf(false, true)) {
                        val config = Configuration(ctx.resources.configuration).apply {
                            this.fontScale = fontScale
                        }
                        val themed = ContextThemeWrapper(ctx.createConfigurationContext(config),
                            R.style.Theme_MAGuardian_NoActionBar)
                        val root = LayoutInflater.from(themed)
                            .inflate(R.layout.item_threat, LinearLayout(themed), false) as ViewGroup
                        root.findViewById<TextView>(R.id.tvThreatName).text = "Aplicativo de teste"
                        root.findViewById<TextView>(R.id.tvThreatPackage).text = "com.example.layouttest"
                        root.findViewById<TextView>(R.id.tvThreatType).text = "Pop-up Invasivo"
                        root.findViewById<TextView>(R.id.tvThreatSeverity).text = "ALTO"
                        root.findViewById<View>(R.id.btnAddToCatalog).visibility =
                            if (admin) View.VISIBLE else View.GONE
                        val density = themed.resources.displayMetrics.density
                        val width = (widthDp * density).toInt()
                        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        root.layout(0, 0, width, root.measuredHeight)
                        val context = "width=$widthDp font=$fontScale admin=$admin"
                        val ids = mutableListOf(R.id.tvThreatName, R.id.tvThreatPackage,
                            R.id.tvThreatType, R.id.tvThreatSeverity, R.id.btnKeep, R.id.btnUninstall)
                        if (admin) ids.add(R.id.btnAddToCatalog)
                        val cardBounds = Rect(0, 0, root.width, root.height)
                        for (id in ids) {
                            val child = root.findViewById<View>(id)
                            assertEquals(context, View.VISIBLE, child.visibility)
                            assertTrue("$context id=$id has positive dimensions",
                                child.width > 0 && child.height > 0)
                            val bounds = Rect(0, 0, child.width, child.height)
                            root.offsetDescendantRectToMyCoords(child, bounds)
                            assertTrue("$context id=$id stays inside the card", cardBounds.contains(bounds))
                        }
                        for (id in listOf(R.id.btnKeep, R.id.btnUninstall)) {
                            val button = root.findViewById<View>(id)
                            assertTrue("$context button=$id is touch-sized", button.height >= 48 * density - 1)
                            assertTrue("$context button=$id is enabled and clickable",
                                button.isEnabled && button.isClickable)
                        }
                        assertTrue("$context must not grow into a blank full-screen panel",
                            root.height < 1200 * density)
                        if (widthDp == 360 && fontScale == 1f) {
                            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                            root.draw(Canvas(bitmap))
                            File(ctx.cacheDir, if (admin) "threat-card-admin.png" else "threat-card-user.png")
                                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
    }

    // Center in the real ScrollView rather than aligning to the window's bottom:
    // API 36 edge-to-edge insets can leave a requested rectangle behind navigation.
    // Never bypass the real button's visibility or click constraints.
    private fun settledScroll(): ViewAction = object : ViewAction {
        override fun getDescription() = "scroll and wait until the button is actually visible"
        override fun getConstraints(): Matcher<View> = allOf(
            withEffectiveVisibility(Visibility.VISIBLE),
            isDescendantOfA(isAssignableFrom(ScrollView::class.java))
        )
        override fun perform(controller: UiController, view: View) {
            var parent = view.parent
            while (parent != null && parent !is ScrollView) parent = parent.parent
            val scroll = checkNotNull(parent as? ScrollView)
            val position = IntArray(2)
            val origin = IntArray(2)
            repeat(20) {
                view.getLocationOnScreen(position)
                scroll.getLocationOnScreen(origin)
                val centered = scroll.scrollY + position[1] - origin[1] -
                    (scroll.height - view.height) / 2
                scroll.scrollTo(scroll.scrollX, centered.coerceAtLeast(0))
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
