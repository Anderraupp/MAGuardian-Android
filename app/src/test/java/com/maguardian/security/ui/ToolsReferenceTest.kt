package com.maguardian.security.ui

import android.app.Application
import android.app.NotificationManager
import android.content.res.Configuration
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.maguardian.security.R
import com.maguardian.security.util.PrefsHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolsReferenceTest {
    @Test fun groupedRowsRetainOriginalRoutesAndReflectRealNotificationPermission() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("maguardian_prefs", 0).edit().clear().commit()
        PrefsHelper.setCallAlertsEnabled(ctx, false)
        PrefsHelper.setBlockTelemarketingEnabled(ctx, false)
        val manager = ctx.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(false)
        for (palette in listOf("original", "petroleo", "gold")) {
            ctx.getSharedPreferences("guardian_appearance", 0).edit().putString("palette", palette).commit()
            val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
            try {
                val activity = controller.get()
                GuardianNavigation.selectMain(activity, true)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.toolsAuxiliary).visibility)
                for (id in listOf(R.id.btnLinkChecker, R.id.btnAppCache, R.id.btnReportNumber,
                    R.id.btnToolsPermissions, R.id.btnUpdateCatalog, R.id.btnScanHistory, R.id.btnToolsLegal)) {
                    val row = activity.findViewById<TextView>(id)
                    assertTrue(row.hasOnClickListeners())
                    assertNotNull(row.compoundDrawables[0])
                    assertNotNull(row.compoundDrawables[2])
                }
                val notifications = activity.findViewById<SwitchCompat>(R.id.btnToolsNotifications)
                assertFalse(notifications.isChecked)
                notifications.performClick()
                assertFalse("Um toque não concede a permissão", notifications.isChecked)
                assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, shadowOf(activity).nextStartedActivity.action)
                shadowOf(manager).setNotificationsEnabled(true)
                GuardianTools.refresh(activity)
                assertTrue(notifications.isChecked)
                shadowOf(manager).setNotificationsEnabled(false)
                GuardianTools.refresh(activity)
                activity.findViewById<View>(R.id.btnLinkChecker).performClick()
                assertEquals(LinkCheckerActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
                activity.findViewById<View>(R.id.btnAppCache).performClick()
                assertEquals(AppCacheActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
                activity.findViewById<View>(R.id.btnScanHistory).performClick()
                assertEquals(ScanHistoryActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
                assertFalse(PrefsHelper.isSubscriptionActive(activity))
                NativeLayoutChecks.capture(activity, "$palette-ferramentas-referencia")
                assertTrue(activity.findViewById<TextView>(R.id.btnLinkChecker).compoundPaddingLeft >=
                    (54 * activity.resources.displayMetrics.density).toInt())
                activity.findViewById<View>(R.id.btnToolsLegal).performClick()
                val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
                val list = dialog.listView
                assertEquals("Política de privacidade", list.adapter.getItem(0).toString())
                assertEquals(4, list.adapter.count)
                list.performItemClick(list.adapter.getView(3, null, list), 3, list.adapter.getItemId(3))
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.toolsAuxiliary).visibility)
                for (id in listOf(R.id.btnCatalogDetails, R.id.btnClearThreats, R.id.btnPermissions,
                     R.id.btnToolsPrivacy, R.id.btnRequestDataDeletion, R.id.btnCatalogSources)) {
                    assertTrue("Ação original preservada: ${activity.resources.getResourceEntryName(id)}",
                        activity.findViewById<View>(id).hasOnClickListeners())
                }
                // The published controller only binds this runtime-permission button when needed.
                if (activity.findViewById<View>(R.id.layoutNotifWarning).visibility == View.VISIBLE)
                    assertTrue(activity.findViewById<View>(R.id.btnNotifPermission).hasOnClickListeners())
            } finally { controller.stop().destroy() }
        }
    }

    @Test fun rowsGrowInsteadOfCuttingTextWithLargeFonts() {
        val ctx = RuntimeEnvironment.getApplication()
        val configuration = Configuration(ctx.resources.configuration).apply { fontScale = 2f; screenWidthDp = 320 }
        ctx.resources.updateConfiguration(configuration, ctx.resources.displayMetrics)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        try {
            val activity = controller.get()
            GuardianNavigation.selectMain(activity, true)
            val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
            val density = activity.resources.displayMetrics.density
            NativeLayoutChecks.measure(root, (320 * density).toInt(), (874 * density).toInt())
            NativeLayoutChecks.visibleTextFits(root)
            for (id in listOf(R.id.btnLinkChecker, R.id.btnAppCache, R.id.switchBlockTelemarketing,
                R.id.switchCallAlerts, R.id.btnToolsNotifications, R.id.btnToolsLegal, R.id.btnAppearance)) {
                assertTrue(activity.findViewById<View>(id).height >= (48 * density).toInt())
            }
        } finally { controller.stop().destroy() }
    }
}
