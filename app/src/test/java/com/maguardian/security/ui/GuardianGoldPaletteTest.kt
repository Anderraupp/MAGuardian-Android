package com.maguardian.security.ui

import android.app.Application
import android.util.TypedValue
import androidx.core.graphics.ColorUtils
import com.maguardian.security.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
class GuardianGoldPaletteTest {
    @Test fun bothDarkThemesShareGoldAndEveryThemeHasLegibleButtonAndBodyText() {
        val ctx = RuntimeEnvironment.getApplication()
        val darkStops = ArrayList<List<Int>>()
        for (palette in listOf("original", "petroleo", "gold")) {
            ctx.getSharedPreferences("guardian_appearance", 0).edit().putString("palette", palette).commit()
            val controller = Robolectric.buildActivity(GuardianStartupTest.PaletteActivity::class.java).create().start()
            val activity = controller.get()
            try {
                fun color(attr: Int): Int = TypedValue().also { assertTrue(activity.theme.resolveAttribute(attr, it, true)) }.data
                val stops = listOf(R.attr.guardian_primary_start, R.attr.guardian_primary_middle, R.attr.guardian_primary_end).map(::color)
                val text = GuardianAppearance.color(activity, R.color.guardian_button_text)
                stops.forEach { assertTrue("$palette botão", ColorUtils.calculateContrast(text, it) >= 4.5) }
                for (surface in listOf(R.color.guardian_paper, R.color.guardian_card)) {
                    for (foreground in listOf(R.color.guardian_ink, R.color.guardian_muted)) {
                        assertTrue("$palette texto", ColorUtils.calculateContrast(GuardianAppearance.color(activity, foreground),
                            GuardianAppearance.color(activity, surface)) >= 4.5)
                    }
                }
                if (palette != "petroleo") { assertEquals(3, stops.distinct().size); darkStops += stops }
                else assertEquals(1, stops.distinct().size)
            } finally { controller.stop().destroy() }
        }
        assertEquals(darkStops[0], darkStops[1])
    }
}
