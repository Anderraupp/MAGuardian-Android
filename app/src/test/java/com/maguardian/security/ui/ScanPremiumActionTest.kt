package com.maguardian.security.ui

import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.widget.Button
import com.maguardian.security.data.ManualScanReport
import com.maguardian.security.util.PrefsHelper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
class ScanPremiumActionTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<MainActivity>? = null

    @Before fun isolatePreferences() {
        ctx.getSharedPreferences("maguardian_prefs", 0).edit().clear()
            .putLong("trial_start_date", System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8)).commit()
        PrefsHelper.setProtectionEnabled(ctx, false)
    }

    @After fun disposeActivity() {
        ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
        controller?.destroy()
    }

    private fun showResult(subscribed: Boolean, withFinding: Boolean = false): Pair<MainActivity, AlertDialog> {
        val current = Robolectric.buildActivity(MainActivity::class.java).create().start()
        controller = current
        val activity = current.get()
        PrefsHelper.setSubscriptionActive(activity, subscribed)
        val report = ManualScanReport()
        if (withFinding) report.record(
            ManualScanReport.Finding("org.example.fixture", "Fixture", "Indício de teste")
        )
        MainActivity::class.java.getDeclaredMethod("showFreeScanResult", ManualScanReport::class.java)
            .apply { isAccessible = true }.invoke(activity, report)
        return activity to ShadowAlertDialog.getLatestAlertDialog()
    }

    @Test fun unsubscribedTestApkHasPremiumActionOpeningTheExistingSubscriptionScreen() {
        val (activity, dialog) = showResult(subscribed = false, withFinding = true)
        assertEquals("Seja Premium", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals("Fechar", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
        assertTrue(shadowOf(dialog).message.toString().contains("1 app(s)"))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val target = shadowOf(activity).nextStartedActivityForResult
        assertNotNull(target)
        assertEquals(SubscriptionActivity::class.java.name, target.intent.component?.className)
        assertFalse(PrefsHelper.isSubscriptionActive(activity))
    }

    @Test fun subscribedUserIsNotAskedToSubscribeAgain() {
        val (_, dialog) = showResult(subscribed = true)
        val action: Button? = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        assertTrue(action == null || action.visibility != android.view.View.VISIBLE)
        assertEquals("Fechar", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
        assertEquals("Nenhuma ameaça identificada nesta verificação.", shadowOf(dialog).message.toString())
    }

    @Test fun closingDoesNotOpenSubscriptionOrUnlockAnything() {
        val (activity, dialog) = showResult(subscribed = false)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        assertFalse(PrefsHelper.isSubscriptionActive(activity))
        assertFalse(PrefsHelper.hasFullAccess(activity))
    }
}
