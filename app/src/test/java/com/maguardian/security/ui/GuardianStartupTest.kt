package com.maguardian.security.ui

import android.app.Application
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.maguardian.security.R
import com.maguardian.security.data.ManualScanReport
import org.junit.Assert.*
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
            GuardianAppearance.apply(this); super.onCreate(savedInstanceState); setContentView(R.layout.activity_main)
        }
    }
    @Test fun allPalettesInflateNativeScreensWithFiveOrderedDestinations() {
        val ctx = RuntimeEnvironment.getApplication()
        for (palette in listOf("original", "petroleo", "gold")) {
            ctx.getSharedPreferences("guardian_appearance", 0).edit().putString("palette", palette).commit()
            val controller = Robolectric.buildActivity(PaletteActivity::class.java).create().start()
            val activity = controller.get()
            try {
                for (layout in listOf(R.layout.activity_main, R.layout.activity_link_checker,
                    R.layout.activity_app_cache, R.layout.activity_subscription, R.layout.activity_auxiliary)) {
                    activity.setContentView(layout)
                    GuardianNavigation.bind(activity)
                    val menu = activity.findViewById<BottomNavigationView>(R.id.guardianBottomNav).menu
                    assertEquals(listOf("Início", "Ferramentas", "Premium", "Sobre", "Atendimento"),
                        (0 until menu.size()).map { menu.getItem(it).title.toString() })
                }
                activity.setContentView(R.layout.activity_welcome)
                assertNotNull(activity.findViewById<View>(R.id.btnWelcomeStart))
            } finally { controller.stop().destroy() }
        }
    }
    @Test fun themePickerKeepsIdsAndLivesInsideTools() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("guardian_appearance", 0)
        prefs.edit().putString("palette", "petroleo").commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        val activity = controller.get()
        try {
            activity.findViewById<BottomNavigationView>(R.id.guardianBottomNav).menu.performIdentifierAction(R.id.navGuardianDesigner, 0)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.guardianHomeContent).visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.guardianToolsContent).visibility)
            activity.findViewById<View>(R.id.btnAppearance).performClick()
            val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val list = dialog.listView
            assertEquals(listOf("Preto e dourado", "Claro", "Vinho e dourado"), (0 until list.adapter.count).map { list.adapter.getItem(it).toString() })
            assertEquals(1, list.checkedItemPosition)
            list.performItemClick(list.adapter.getView(2, null, list), 2, list.adapter.getItemId(2))
            assertEquals("gold", prefs.getString("palette", null))
        } finally { if (!activity.isDestroyed) controller.stop().destroy() }
    }
    @Test fun contactDestinationUsesActualScreenAndAboutUsesConfiguredUrl() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        try {
            val activity = controller.get()
            val menu = activity.findViewById<BottomNavigationView>(R.id.guardianBottomNav).menu
            menu.performIdentifierAction(R.id.navGuardianContact, 0)
            assertEquals(SupportActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
            menu.performIdentifierAction(R.id.navGuardianAbout, 0)
            assertEquals(GuardianExternal.ABOUT, shadowOf(activity).nextStartedActivity.data.toString())
        } finally { controller.stop().destroy() }
    }
    @Test fun permissionReviewsDoNotShowTestDiagnostics() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        try {
            val report = ManualScanReport().apply {
                record(ManualScanReport.Finding("example.legitimate", "App legítimo", "Permissões declaradas não comprovam ameaça", permissionOnly = true))
            }
            MainActivity::class.java.getDeclaredMethod("showFreeScanResult", ManualScanReport::class.java)
                .apply { isAccessible = true }.invoke(controller.get(), report)
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals("Nenhuma ameaça identificada nesta verificação.", shadowOf(dialog).message.toString())
            assertEquals("Seja Premium", dialog.getButton(DialogInterface.BUTTON_POSITIVE).text.toString())
            assertEquals("Fechar", dialog.getButton(DialogInterface.BUTTON_NEGATIVE).text.toString())
            dialog.dismiss()
        } finally { controller.stop().destroy() }
    }
}
