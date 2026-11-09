package com.maguardian.security.ui

import android.app.Application
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.maguardian.security.R
import com.maguardian.security.data.ManualScanReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

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

    @Test fun opensActualMainActivity() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals(5, controller.get().findViewById<BottomNavigationView>(R.id.guardianBottomNav).menu.size())
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
        assertFalse(message.contains("permissões", ignoreCase = true))
        assertFalse(message.contains("Relatório local"))
        assertEquals(View.GONE, dialog.getButton(DialogInterface.BUTTON_POSITIVE).visibility)
        assertEquals("Fechar", dialog.getButton(DialogInterface.BUTTON_NEGATIVE).text.toString())
        dialog.dismiss()
        controller.pause().stop().destroy()
    }
}
