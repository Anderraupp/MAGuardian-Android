package com.maguardian.security.ui

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.maguardian.security.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayoutReferenceCaptureTest {
    @Test fun capturesActualNativeScreensAndChecksAllThreeThemes() {
        RuntimeEnvironment.setQualifiers("w402dp-h874dp")
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("maguardian_prefs", 0).edit().clear().commit()
        val screenshots = listOf("original", "petroleo", "gold")
        for (palette in screenshots) {
            ctx.getSharedPreferences("guardian_appearance", 0).edit().clear().putString("palette", palette).commit()
            val welcome = Robolectric.buildActivity(WelcomeActivity::class.java).create().start()
            try {
                NativeLayoutChecks.capture(welcome.get(), "$palette-boas-vindas")
                assertNotNull(welcome.get().findViewById<View>(R.id.btnWelcomeRestore))
            } finally { welcome.stop().destroy() }
            val main = Robolectric.buildActivity(MainActivity::class.java).create().start()
            try {
                MainActivity::class.java.getDeclaredMethod("refreshUI").apply { isAccessible = true }.invoke(main.get())
                NativeLayoutChecks.capture(main.get(), "$palette-inicio")
                GuardianNavigation.selectMain(main.get(), true)
                NativeLayoutChecks.capture(main.get(), "$palette-ferramentas")
                main.get().findViewById<View>(R.id.btnToolsBack).performClick()
                assertEquals(View.VISIBLE, main.get().findViewById<View>(R.id.guardianHomeContent).visibility)
            } finally { main.stop().destroy() }
            val link = Robolectric.buildActivity(LinkCheckerActivity::class.java).create().start()
            try {
                NativeLayoutChecks.capture(link.get(), "$palette-links")
                assertNotNull(link.get().findViewById<TextView>(R.id.btnPaste).compoundDrawables[0])
                assertNotNull(link.get().findViewById<TextView>(R.id.btnCheck).compoundDrawables[0])
                link.get().findViewById<View>(R.id.btnBack).performClick()
                assertTrue(link.get().isFinishing)
            } finally { link.stop().destroy() }
            val cache = Robolectric.buildActivity(AppCacheActivity::class.java).create().start()
            try {
                AppCacheActivity::class.java.getDeclaredMethod("refreshMeasurements").apply { isAccessible = true }.invoke(cache.get())
                val work = AppCacheActivity::class.java.getDeclaredField("pending").apply { isAccessible = true }.get(cache.get()) as Future<*>
                work.get(10, TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idle()
                NativeLayoutChecks.capture(cache.get(), "$palette-cache")
                assertEquals(View.VISIBLE, cache.get().findViewById<View>(R.id.tvCacheLimitations).visibility)
            } finally { cache.stop().destroy() }
            val premium = Robolectric.buildActivity(SubscriptionActivity::class.java).create().start()
            try {
                SubscriptionActivity::class.java.getDeclaredMethod("updatePrice").apply { isAccessible = true }.invoke(premium.get())
                NativeLayoutChecks.capture(premium.get(), "$palette-premium")
                assertEquals("R$ 9,99", premium.get().findViewById<TextView>(R.id.tvMonthlyPrice).text.toString())
                assertEquals(View.VISIBLE, premium.get().findViewById<View>(R.id.tvPriceReference).visibility)
                assertEquals(View.GONE, premium.get().findViewById<View>(R.id.tvPlayOffer).visibility)
                val button = premium.get().findViewById<View>(R.id.btnSubscribe)
                val nav = premium.get().findViewById<View>(R.id.guardianBottomNav)
                val root = premium.get().findViewById<ViewGroup>(android.R.id.content)
                val buttonBounds = android.graphics.Rect(); val navBounds = android.graphics.Rect()
                button.getDrawingRect(buttonBounds); nav.getDrawingRect(navBounds)
                root.offsetDescendantRectToMyCoords(button, buttonBounds)
                root.offsetDescendantRectToMyCoords(nav, navBounds)
                assertTrue("Assinatura visível antes da barra em $palette",
                    buttonBounds.bottom <= navBounds.top)
                assertFalse(com.maguardian.security.util.PrefsHelper.isSubscriptionActive(ctx))
            } finally { premium.stop().destroy() }
            val support = Robolectric.buildActivity(SupportActivity::class.java).create().start()
            try {
                NativeLayoutChecks.capture(support.get(), "$palette-atendimento")
                val text = StringBuilder()
                fun collect(view: View) {
                    if (view is TextView) text.append(view.text).append('\n')
                    if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
                }
                collect(support.get().findViewById(android.R.id.content))
                assertTrue(text.contains("Anderson ou Vanessa"))
                assertTrue(text.contains(ctx.getString(R.string.guardian_support_email)))
                assertTrue(text.contains(ctx.getString(R.string.guardian_support_whatsapp_display)))
                assertTrue(text.contains("@meacelulareseacessorios"))
            } finally { support.stop().destroy() }
            val history = Robolectric.buildActivity(ScanHistoryActivity::class.java).create().start()
            try { NativeLayoutChecks.capture(history.get(), "$palette-historico") }
            finally { history.stop().destroy() }
        }
    }
}
