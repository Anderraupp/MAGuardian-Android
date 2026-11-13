package com.maguardian.security.ui

import android.app.Application
import android.view.View
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
class GuardianSymmetryTest {
    @Test fun equalMetricCardsAndSettingsFitSmallScreensAndEnlargedFonts() {
        val ctx = RuntimeEnvironment.getApplication()
        for (scale in listOf(1f, 1.5f)) for (width in listOf(320, 402)) {
            RuntimeEnvironment.setQualifiers("w${width}dp-h874dp")
            val config = android.content.res.Configuration(ctx.resources.configuration).apply { fontScale = scale }
            ctx.resources.updateConfiguration(config, ctx.resources.displayMetrics)
            for (palette in listOf("original", "petroleo", "gold")) {
                ctx.getSharedPreferences("guardian_appearance", 0).edit().putString("palette", palette).commit()
                val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
                try {
                    val activity = controller.get()
                    val home = activity.findViewById<View>(R.id.guardianHomeContent)
                    val pixels = ((width - 32) * activity.resources.displayMetrics.density).toInt()
                    NativeLayoutChecks.measure(home, pixels)
                    NativeLayoutChecks.visibleTextFits(home)
                    val cards = listOf(R.id.tvTotalFound, R.id.tvTotalRemoved, R.id.tvTotalScans, R.id.tvLastScan)
                        .map { activity.findViewById<TextView>(it).parent.parent as View }
                    assertTrue(kotlin.math.abs(cards[0].width - cards[1].width) <= 1)
                    assertEquals(cards[0].height, cards[1].height)
                    assertEquals(cards[2].height, cards[3].height)
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.tvCacheSize).visibility)
                    GuardianNavigation.selectMain(activity, true)
                    val tools = activity.findViewById<View>(R.id.guardianToolsContent)
                    NativeLayoutChecks.measure(tools, pixels)
                    NativeLayoutChecks.visibleTextFits(tools)
                    val links = activity.findViewById<View>(R.id.btnLinkChecker)
                    val cache = activity.findViewById<View>(R.id.btnAppCache)
                    assertEquals(links.width, cache.width)
                    assertEquals(links.left, cache.left)
                } finally { controller.stop().destroy() }
            }
        }
    }
}
