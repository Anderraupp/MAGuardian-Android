package com.maguardian.security.ui

import android.app.Application
import android.content.res.Configuration
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.maguardian.security.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactCardsTest {
    private fun configure(width: Int, font: Float) {
        RuntimeEnvironment.setQualifiers("w${width}dp-h874dp")
        val resources = RuntimeEnvironment.getApplication().resources
        resources.updateConfiguration(Configuration(resources.configuration).apply { fontScale = font }, resources.displayMetrics)
    }
    private fun renderCache(activity: AppCacheActivity, longNames: Boolean, count: Int = 4) {
        val entryType = AppCacheActivity::class.java.declaredClasses.first { it.simpleName == "CacheEntry" }
        val constructor = entryType.declaredConstructors.first { it.parameterCount == 3 }.apply { isAccessible = true }
        // Inert layout fixtures only; production measurements are not replaced.
        val entries = (1..count).map {
            constructor.newInstance("org.layout.fixture$it",
                if (longNames) "Aplicativo de teste com nome muito longo $it" else "App $it", 1048576L)
        }
        AppCacheActivity::class.java.getDeclaredMethod("renderEntries", List::class.java).apply { isAccessible = true }
            .invoke(activity, entries)
    }
    @Test fun defaultCardsAreCompactSymmetricAndIdenticalAcrossThemes() {
        configure(402, 1f)
        val ctx = RuntimeEnvironment.getApplication()
        var metricHeight: Int? = null
        var cacheHeight: Int? = null
        for (palette in listOf("original", "petroleo", "gold")) {
            ctx.getSharedPreferences("guardian_appearance", 0).edit().putString("palette", palette).commit()
            val main = Robolectric.buildActivity(MainActivity::class.java).create().start()
            try {
                val activity = main.get()
                val density = activity.resources.displayMetrics.density
                val home = activity.findViewById<View>(R.id.guardianHomeContent)
                NativeLayoutChecks.measure(home, (370 * density).toInt())
                NativeLayoutChecks.visibleTextFits(home)
                val cards = listOf(R.id.tvTotalFound, R.id.tvTotalRemoved, R.id.tvTotalScans, R.id.tvLastScan)
                    .map { activity.findViewById<TextView>(it).parent.parent as LinearLayout }
                cards.forEach {
                    assertEquals(LinearLayout.HORIZONTAL, it.orientation)
                    assertEquals(2, it.childCount)
                    assertTrue("Resumo compacto: ${it.height / density}dp", it.height <= 64 * density)
                    if (metricHeight == null) metricHeight = it.height
                    assertEquals(metricHeight, it.height)
                }
            } finally { main.stop().destroy() }
            val cache = Robolectric.buildActivity(AppCacheActivity::class.java).create().start()
            try {
                val activity = cache.get()
                renderCache(activity, false)
                val rows = activity.findViewById<LinearLayout>(R.id.llAppCaches)
                val density = activity.resources.displayMetrics.density
                NativeLayoutChecks.measure(rows, (370 * density).toInt())
                NativeLayoutChecks.visibleTextFits(rows)
                assertEquals(2, rows.childCount)
                for (rowIndex in 0 until rows.childCount) {
                    val pair = rows.getChildAt(rowIndex) as LinearLayout
                    assertEquals(2, pair.childCount)
                    for (column in 0..1) {
                        val card = pair.getChildAt(column) as LinearLayout
                        assertEquals(LinearLayout.HORIZONTAL, (card.getChildAt(0) as LinearLayout).orientation)
                        assertTrue("Cache compacto: ${card.height / density}dp", card.height <= 112 * density)
                        val open = card.getChildAt(1) as Button
                        assertTrue(open.height >= 48 * density)
                        if (cacheHeight == null) cacheHeight = card.height
                        assertEquals(cacheHeight, card.height)
                    }
                }
            } finally { cache.stop().destroy() }
        }
    }
    @Test fun longNamesAndLargeFontsExpandWithoutHidingTextOrActions() {
        configure(320, 2f)
        val controller = Robolectric.buildActivity(AppCacheActivity::class.java).create().start()
        try {
            val activity = controller.get()
            renderCache(activity, true)
            val rows = activity.findViewById<LinearLayout>(R.id.llAppCaches)
            NativeLayoutChecks.measure(rows, (288 * activity.resources.displayMetrics.density).toInt())
            assertEquals(4, rows.childCount) // One column when the font is enlarged.
            NativeLayoutChecks.visibleTextFits(rows)
            for (i in 0 until rows.childCount) {
                val card = rows.getChildAt(i) as LinearLayout
                assertEquals("Abrir", (card.getChildAt(card.childCount - 1) as Button).text.toString())
            }
        } finally { controller.stop().destroy() }
    }
    @Test fun anOddFinalCardRemainsVisibleInsideTheRealScrollableLayout() {
        configure(402, 1f)
        val controller = Robolectric.buildActivity(AppCacheActivity::class.java).create().start()
        try {
            val activity = controller.get()
            renderCache(activity, false, 1)
            val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
            val density = activity.resources.displayMetrics.density
            NativeLayoutChecks.measure(root, (402 * density).toInt(), (874 * density).toInt())
            val rows = activity.findViewById<LinearLayout>(R.id.llAppCaches)
            val card = (rows.getChildAt(0) as LinearLayout).getChildAt(0)
            assertTrue("O cartão ímpar não pode desaparecer", card.height >= 80 * density)
            NativeLayoutChecks.visibleTextFits(card)
        } finally { controller.stop().destroy() }
    }
}
