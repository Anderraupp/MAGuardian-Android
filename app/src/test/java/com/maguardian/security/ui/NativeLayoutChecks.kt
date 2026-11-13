package com.maguardian.security.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import org.junit.Assert.*

internal object NativeLayoutChecks {
    fun measure(view: View, width: Int, height: Int? = null) {
        repeat(3) {
            view.forceLayout()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height ?: 0, if (height == null) View.MeasureSpec.UNSPECIFIED else View.MeasureSpec.EXACTLY))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }
    }
    fun visibleTextFits(view: View) {
        if (view.visibility != View.VISIBLE) return
        if (view is TextView && view.text.isNotBlank() && view.width > 0 && view.height > 0) {
            view.draw(Canvas())
            val layout = view.layout
            assertNotNull("Layout: ${view.text}", layout)
            assertEquals("Texto inteiro: ${view.text}", view.text.length, layout.getLineEnd(layout.lineCount - 1))
            for (line in 0 until layout.lineCount) {
                assertEquals("Sem reticências: ${view.text}", 0, layout.getEllipsisCount(line))
            }
            assertTrue("Altura suficiente: ${view.text} (${layout.height}/${view.height})",
                layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom + 1)
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) visibleTextFits(view.getChildAt(i))
    }
    fun capture(activity: AppCompatActivity, name: String) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val density = activity.resources.displayMetrics.density
        measure(root, (402 * density).toInt(), (874 * density).toInt())
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File(System.getProperty("java.io.tmpdir"), "guardian-redesign/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val scrollId = activity.resources.getIdentifier("guardianScrollContent", "id", activity.packageName)
        val scroll = if (scrollId != 0) activity.findViewById<android.widget.ScrollView>(scrollId) else null
        val body = scroll?.getChildAt(0)
        if (body != null && body.height > scroll.height && body.width > 0) {
            val full = Bitmap.createBitmap(body.width, body.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(full)
            canvas.drawColor(GuardianAppearance.color(activity, com.maguardian.security.R.color.guardian_paper))
            body.draw(canvas)
            File(file.parentFile, "$name-conteudo.png").outputStream().use { full.compress(Bitmap.CompressFormat.PNG, 100, it) }
            full.recycle()
        }
    }
}
