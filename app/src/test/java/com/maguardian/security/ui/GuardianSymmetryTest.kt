package com.maguardian.security.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
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
class GuardianSymmetryTest {
    @Test fun metricsAndToolsHaveEqualBoundsAndAlignedLabels() {
        val ctx = RuntimeEnvironment.getApplication()
        for (palette in listOf("original", "petroleo", "gold")) {
            ctx.getSharedPreferences("guardian_appearance", 0).edit().putString("palette", palette).commit()
            val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
            val activity = controller.get()
            try {
                val values = listOf(R.id.tvTotalFound, R.id.tvTotalRemoved, R.id.tvTotalScans, R.id.tvLastScan)
                    .map { activity.findViewById<TextView>(it) }
                val cards = values.map { it.parent as LinearLayout }
                val grid = cards.first().parent.parent as View
                val tools = listOf(R.id.btnLinkChecker, R.id.btnCallScanner, R.id.btnCleanCache, R.id.btnAppCache)
                    .map { activity.findViewById<TextView>(it) }
                val toolRows = listOf(tools[0].parent as View, tools[2].parent as View)
                for (screenDp in listOf(320, 360, 402)) {
                    val width = ((screenDp - 40) * activity.resources.displayMetrics.density).toInt()
                    for (lastScan in listOf("Nunca", "12/11\n19:35")) {
                        values.last().text = lastScan
                        values[2].text = "12345"
                        repeat(3) {
                            for (view in listOf(grid) + toolRows) {
                                view.forceLayout()
                                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                            }
                        }
                        for (card in cards) {
                            assertNotNull(card.background)
                            assertTrue(kotlin.math.abs(cards.first().width - card.width) <= 1)
                            assertEquals(cards.first().height, card.height)
                            assertEquals(cards.first().paddingLeft, card.paddingRight)
                            assertEquals(cards.first().paddingTop, card.paddingBottom)
                            assertEquals(cards.first().getChildAt(1).top, card.getChildAt(1).top)
                        }
                        assertEquals(cards[0].left, cards[2].left)
                        assertEquals(cards[1].left, cards[3].left)
                        assertEquals(cards[0].top, cards[1].top)
                        assertEquals(cards[2].top, cards[3].top)
                        val horizontalGap = cards[1].left - cards[0].right
                        val firstRow = cards[0].parent as View
                        val secondRow = cards[2].parent as View
                        assertEquals(horizontalGap, secondRow.top - firstRow.bottom)
                        for (card in cards) {
                            for (index in 0 until card.childCount) {
                                val text = card.getChildAt(index) as TextView
                                text.draw(Canvas())
                                assertTrue("Texto cortado em $palette/$screenDp: ${text.text}",
                                    text.layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                                assertEquals(text.text.length, text.layout.getLineEnd(text.lineCount - 1))
                            }
                        }
                        for (tool in tools) {
                            assertEquals(tools.first().height, tool.height)
                            assertEquals(tools.first().paddingTop, tool.paddingTop)
                            assertEquals(tools.first().minLines, tool.minLines)
                        }
                        assertEquals(View.GONE, activity.findViewById<View>(R.id.tvCacheSize).visibility)
                    }
                    if (palette == "original" && screenDp == 360 && android.os.Build.VERSION.SDK_INT == 34) {
                        val gap = (14 * activity.resources.displayMetrics.density).toInt()
                        val bitmap = Bitmap.createBitmap(width, grid.height + toolRows.sumOf { it.height } + gap * 2,
                            Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(0xFF0A0808.toInt())
                        grid.draw(canvas)
                        canvas.translate(0f, (grid.height + gap).toFloat())
                        toolRows[0].draw(canvas)
                        canvas.translate(0f, (toolRows[0].height + gap).toFloat())
                        toolRows[1].draw(canvas)
                        java.io.File(System.getProperty("java.io.tmpdir"), "guardian-symmetry-preview.png")
                            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }
            } finally {
                if (!activity.isDestroyed) controller.stop().destroy()
            }
        }
    }
}
