package com.maguardian.security.ui

import android.app.Application
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import com.maguardian.security.R
import com.maguardian.security.util.PrefsHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
class GuardianHeaderTest {
    @Test fun proReflectsSubscriptionNotTrialAndTaglineStaysClose() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("maguardian_prefs", 0).edit().clear().commit()
        PrefsHelper.initTrial(ctx)
        for (palette in listOf("original", "petroleo", "gold")) {
            ctx.getSharedPreferences("guardian_appearance", 0).edit().putString("palette", palette).commit()
            val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
            val activity = controller.get()
            try {
                val refresh = MainActivity::class.java.getDeclaredMethod("refreshUI").apply { isAccessible = true }
                val title = activity.findViewById<TextView>(R.id.tvAppTitle)
                val tagline = activity.findViewById<TextView>(R.id.tvAppTagline)
                for (subscribed in listOf(false, true, false)) {
                    PrefsHelper.setSubscriptionActive(ctx, subscribed)
                    refresh.invoke(activity)
                    assertEquals(if (subscribed) "M&A Guardian PRO" else "M&A Guardian", title.text.toString())
                    if (subscribed) {
                        val text = title.text as Spanned
                        val blue = text.getSpans(0, text.length, ForegroundColorSpan::class.java).single()
                        assertEquals(0xFF2196F3.toInt(), blue.foregroundColor)
                        assertEquals("PRO", text.subSequence(text.getSpanStart(blue), text.getSpanEnd(blue)).toString())
                    }
                    assertEquals(subscribed, PrefsHelper.isSubscriptionActive(ctx))
                }
                assertTrue("Sem altura mínima que afaste a frase", title.minHeight <= 0)
                val titleParams = title.layoutParams as android.view.ViewGroup.MarginLayoutParams
                assertEquals((8 * activity.resources.displayMetrics.density).toInt(), titleParams.bottomMargin)
                assertTrue("Subtítulo pode se adaptar sem fonte excessivamente pequena", tagline.textSize >= 12 * activity.resources.displayMetrics.scaledDensity)
                assertEquals("Proteção inteligente para o seu dispositivo.", tagline.text.toString())
            } finally {
                if (!activity.isDestroyed) controller.stop().destroy()
            }
        }
    }
}
