package com.maguardian.security.ui

import android.app.Application
import android.view.View
import androidx.appcompat.widget.SwitchCompat
import com.maguardian.security.R
import com.maguardian.security.util.PrefsHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
class CallProtectionCardTest {
    @Test fun callControlsHaveRequiredOrderAndNeverPretendMissingPermissionsAreActive() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("maguardian_prefs", 0).edit().clear().commit()
        for (palette in listOf("original", "petroleo", "gold")) {
            ctx.getSharedPreferences("guardian_appearance", 0).edit().putString("palette", palette).commit()
            val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
            try {
                val activity = controller.get()
                GuardianNavigation.selectMain(activity, true)
                val block = activity.findViewById<SwitchCompat>(R.id.switchBlockTelemarketing)
                val alerts = activity.findViewById<SwitchCompat>(R.id.switchCallAlerts)
                assertEquals("Bloquear telemarketing", block.text.toString())
                assertEquals("Alertar ligações suspeitas", alerts.text.toString())
                PrefsHelper.setBlockTelemarketingEnabled(activity, true)
                PrefsHelper.setCallAlertsEnabled(activity, true)
                GuardianTools.refresh(activity)
                assertFalse(block.isChecked)
                assertFalse(alerts.isChecked)
                // False by default permissions is not a persisted change of the user's desired setting.
                assertTrue(PrefsHelper.isCallAlertsEnabled(activity))
                PrefsHelper.setCallAlertsEnabled(activity, false)
                GuardianTools.refresh(activity)
                assertFalse(alerts.isChecked)
                assertFalse(PrefsHelper.isCallAlertsEnabled(activity))
                assertEquals(View.GONE, activity.findViewById<View>(R.id.btnCallScanner).visibility)
                assertTrue(activity.findViewById<View>(R.id.btnReportNumber).hasOnClickListeners())
            } finally { controller.stop().destroy() }
        }
    }
}
