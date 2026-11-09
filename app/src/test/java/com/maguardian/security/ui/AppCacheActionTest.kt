package com.maguardian.security.ui

import android.app.Application
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.maguardian.security.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
class AppCacheActionTest {
    @Test fun compactCenteredActionFitsRowsAndStillOpensTheCorrectApp() {
        for (palette in listOf("original", "petroleo", "gold")) {
            RuntimeEnvironment.getApplication().getSharedPreferences("guardian_appearance", 0)
                .edit().putString("palette", palette).commit()
            // Do not resume: this test inspects an inert row, not live storage measurements.
            val controller = Robolectric.buildActivity(AppCacheActivity::class.java).create().start()
            val activity = controller.get()
            val entryClass = AppCacheActivity::class.java.declaredClasses.single { it.simpleName == "CacheEntry" }
            val entry = entryClass.getDeclaredConstructor(String::class.java, String::class.java,
                java.lang.Long::class.java).apply { isAccessible = true }
                .newInstance("org.example.cachefixture", "Aplicativo com nome longo para conferir o espaço", 4096L)
            AppCacheActivity::class.java.getDeclaredMethod("addEntry", entryClass)
                .apply { isAccessible = true }.invoke(activity, entry)
            val row = activity.findViewById<LinearLayout>(R.id.llAppCaches).getChildAt(0) as LinearLayout
            val button = row.getChildAt(row.childCount - 1) as Button
            val density = activity.resources.displayMetrics.density
            assertEquals("Abrir", button.text.toString())
            assertEquals(Gravity.CENTER, button.gravity and (Gravity.HORIZONTAL_GRAVITY_MASK or Gravity.VERTICAL_GRAVITY_MASK))
            assertEquals(1, button.maxLines)
            assertTrue(button.isClickable)
            assertNotNull(button.contentDescription)
            for (screenDp in listOf(320, 360, 402)) {
                val width = ((screenDp - 40) * density).toInt()
                row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                row.layout(0, 0, width, row.measuredHeight)
                assertTrue("Toque de pelo menos 48dp", button.measuredHeight >= (48 * density).toInt())
                assertTrue("Ação compacta", button.measuredHeight <= (56 * density).toInt())
                assertTrue("Sem formato quadrado", button.measuredWidth > button.measuredHeight)
                assertEquals(1, button.lineCount)
                assertTrue(button.right <= width - row.paddingRight)
                assertTrue(button.left >= row.paddingLeft)
            }
            button.performClick()
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
            assertEquals("package:org.example.cachefixture", intent.data.toString())
            controller.stop().destroy()
        }
    }
}
