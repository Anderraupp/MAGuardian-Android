package com.maguardian.security.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils
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
class GuardianGoldPaletteTest {
    @Test fun goldHasWarmMetallicDepthWithoutRecoloringOtherPalettes() {
        val ctx = RuntimeEnvironment.getApplication()
        for ((palette, expected) in listOf("original" to 0xFFDC2626.toInt(),
            "petroleo" to 0xFF145E5B.toInt(), "gold" to 0xFFE7B53D.toInt())) {
            ctx.getSharedPreferences("guardian_appearance", 0).edit().putString("palette", palette).commit()
            val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
            val activity = controller.get()
            try {
                fun color(attr: Int): Int = TypedValue().also {
                    assertTrue(activity.theme.resolveAttribute(attr, it, true))
                }.data
                assertEquals(expected, color(R.attr.guardian_button_bg))
                val stops = listOf(R.attr.guardian_primary_start, R.attr.guardian_primary_middle,
                    R.attr.guardian_primary_end).map { color(it) }
                if (palette == "gold") {
                    assertEquals(3, stops.distinct().size)
                    stops.forEach {
                        assertTrue("Texto legível sobre todo o dourado",
                            ColorUtils.calculateContrast(color(R.attr.guardian_button_text), it) >= 4.5)
                    }
                    val content = activity.findViewById<View>(R.id.tvAppTitle).parent.parent.parent as View
                    val width = (360 * activity.resources.displayMetrics.density).toInt()
                    repeat(3) {
                        content.forceLayout()
                        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
                    }
                    if (android.os.Build.VERSION.SDK_INT == 34) {
                        val bitmap = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(color(R.attr.guardian_paper))
                        content.draw(canvas)
                        java.io.File(System.getProperty("java.io.tmpdir"), "guardian-gold-preview.png")
                            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                } else {
                    assertEquals(listOf(expected, expected, expected), stops)
                }
            } finally {
                if (!activity.isDestroyed) controller.stop().destroy()
            }
        }
    }
}
