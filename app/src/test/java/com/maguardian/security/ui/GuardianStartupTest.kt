package com.maguardian.security.ui

import android.app.Application
import android.content.DialogInterface
import android.graphics.RectF
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.shape.MaterialShapeDrawable
import com.maguardian.security.R
import com.maguardian.security.data.ManualScanReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
class GuardianStartupTest {
    class PaletteActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            GuardianAppearance.apply(this)
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_main)
            GuardianNavigation.bind(this)
        }
    }

    private fun inflate(palette: String) {
        RuntimeEnvironment.getApplication().getSharedPreferences("guardian_appearance", 0)
            .edit().putString("palette", palette).commit()
        val controller = Robolectric.buildActivity(PaletteActivity::class.java).setup()
        val activity = controller.get()
        val removedShortcut = activity.resources.getIdentifier("btnAppearance", "id", activity.packageName)
        if (removedShortcut != 0) assertNull(activity.findViewById<View>(removedShortcut))
        for (layout in listOf(
            R.layout.activity_main, R.layout.activity_link_checker,
            R.layout.activity_app_cache, R.layout.activity_subscription
        )) {
            activity.setContentView(layout)
            GuardianNavigation.bind(activity)
            assertEquals(5, activity.findViewById<BottomNavigationView>(R.id.guardianBottomNav).menu.size())
            GuardianAppearance.color(activity, R.color.guardian_ink)
        }
        controller.pause().stop().destroy()
    }

    @Test fun opensOriginalPalette() = inflate("original")
    @Test fun opensPetroleoPalette() = inflate("petroleo")
    @Test fun opensGoldPalette() = inflate("gold")

    @Test fun taglineFitsOnOneLineOnSmallScreens() {
        val controller = Robolectric.buildActivity(PaletteActivity::class.java).setup()
        val activity = controller.get()
        val tagline = activity.findViewById<TextView>(R.id.tvAppTagline)
        assertEquals("Proteção Inteligente para o seu dispositivo", tagline.text.toString())
        assertEquals(1, tagline.maxLines)
        assertEquals(TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM, tagline.autoSizeTextType)
        for (screenDp in listOf(320, 360, 402)) {
            val width = ((screenDp - 40 - 72) * activity.resources.displayMetrics.density).toInt()
            tagline.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            tagline.layout(0, 0, width, tagline.measuredHeight)
            assertEquals(1, tagline.lineCount)
            assertEquals(0, tagline.layout.getEllipsisCount(0))
            assertTrue(tagline.paint.measureText(tagline.text.toString()) <= width + 1)
        }
        controller.pause().stop().destroy()
    }

    @Test fun designerShowsNamesAndKeepsTheSavedChoice() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("guardian_appearance", 0)
        prefs.edit().putString("palette", "petroleo").commit()
        val controller = Robolectric.buildActivity(PaletteActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<BottomNavigationView>(R.id.guardianBottomNav).menu
            .performIdentifierAction(R.id.navGuardianDesigner, 0)
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val list = dialog.listView
        val background = dialog.window!!.decorView.background as InsetDrawable
        val surface = background.drawable as MaterialShapeDrawable
        assertEquals(24f * activity.resources.displayMetrics.density,
            surface.shapeAppearanceModel.topLeftCornerSize.getCornerSize(RectF(0f, 0f, 400f, 400f)), 0.1f)
        assertEquals(listOf("Original", "Claro", "Grafite"), (0 until list.adapter.count).map {
            list.adapter.getItem(it).toString()
        })
        assertEquals(1, list.checkedItemPosition)
        list.performItemClick(list.adapter.getView(2, null, list), 2, list.adapter.getItemId(2))
        assertEquals("gold", prefs.getString("palette", null))
        controller.pause().stop().destroy()
    }

    @Test fun introDescriptionFitsOnOneLineOnSmallScreens() {
        val controller = Robolectric.buildActivity(PaletteActivity::class.java).setup()
        val activity = controller.get()
        val description = activity.findViewById<TextView>(R.id.tvIntroDescription)
        assertEquals("Mais controle sobre aplicativos, links e ligações no seu dia a dia.",
            description.text.toString())
        assertEquals(1, description.maxLines)
        assertEquals(TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM, description.autoSizeTextType)
        for (screenDp in listOf(320, 360, 402)) {
            val width = ((screenDp - 40) * activity.resources.displayMetrics.density).toInt()
            description.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            description.layout(0, 0, width, description.measuredHeight)
            assertEquals(1, description.lineCount)
            assertEquals(0, description.layout.getEllipsisCount(0))
            assertTrue(description.paint.measureText(description.text.toString()) <= width + 1)
        }
        controller.pause().stop().destroy()
    }

    @Test fun opensActualMainActivity() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals(5, controller.get().findViewById<BottomNavigationView>(R.id.guardianBottomNav).menu.size())
        controller.pause().stop().destroy()
    }

    @Test fun contactShowsFullPhoneAndEmail() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<BottomNavigationView>(R.id.guardianBottomNav).menu
            .performIdentifierAction(R.id.navGuardianContact, 0)
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val list = dialog.listView
        assertEquals("WhatsApp\n+55 (45) 99848-7232", list.adapter.getItem(0))
        assertEquals("E-mail\ncontato@meacelulareseacessorios.com.br", list.adapter.getItem(1))
        val emailRow = list.adapter.getView(1, null, list) as TextView
        assertTrue(emailRow.maxLines > 1)
        assertEquals(null, emailRow.ellipsize)
        dialog.dismiss()
        controller.pause().stop().destroy()
    }

    @Test fun permissionReviewsDoNotShowTestDiagnostics() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val report = ManualScanReport().apply {
            record(ManualScanReport.Finding("example.legitimate", "App legítimo",
                "Permissões declaradas não comprovam ameaça", permissionOnly = true))
        }
        MainActivity::class.java.getDeclaredMethod("showFreeScanResult", ManualScanReport::class.java)
            .apply { isAccessible = true }.invoke(controller.get(), report)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val message = shadowOf(dialog).message.toString()
        assertEquals("Nenhuma ameaça identificada nesta verificação.", message)
        assertFalse(message.contains("permissões", ignoreCase = true))
        assertFalse(message.contains("Relatório local"))
        assertEquals(View.GONE, dialog.getButton(DialogInterface.BUTTON_POSITIVE).visibility)
        assertEquals("Fechar", dialog.getButton(DialogInterface.BUTTON_NEGATIVE).text.toString())
        dialog.dismiss()
        controller.pause().stop().destroy()
    }
}
