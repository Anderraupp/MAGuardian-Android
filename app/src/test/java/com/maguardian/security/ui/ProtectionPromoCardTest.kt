package com.maguardian.security.ui

import android.app.Application
import android.content.Context
import android.view.View
import android.widget.TextView
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProtectionPromoCardTest {
    @Test fun premiumAvailabilityIsSeparateFromMonitoringAndUsesExistingSubscriptionFlow() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("maguardian_prefs", 0).edit().clear()
            .putLong("trial_start_date", System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8)).commit()
        for (palette in listOf("original", "petroleo", "gold")) {
            ctx.getSharedPreferences("guardian_appearance", 0).edit().putString("palette", palette).commit()
            val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
            val activity = controller.get()
            try {
                MainActivity::class.java.getDeclaredMethod("refreshUI").apply { isAccessible = true }.invoke(activity)
                assertEquals("Disponível", activity.findViewById<TextView>(R.id.tvStatusBadge).text.toString())
                assertEquals("Monitoramento disponível com Premium", activity.findViewById<TextView>(R.id.tvStatus).text.toString())
                val protection = PrefsHelper.isProtectionEnabled(ctx)
                activity.findViewById<View>(R.id.tvStatusBadge).performClick()
                assertEquals(SubscriptionActivity::class.java.name,
                    shadowOf(activity).nextStartedActivityForResult.intent.component?.className)
                assertEquals(protection, PrefsHelper.isProtectionEnabled(ctx))
                assertFalse(PrefsHelper.isSubscriptionActive(ctx))
                val card = activity.findViewById<View>(R.id.cardStatus)
                for (width in listOf(320, 360, 402)) {
                    NativeLayoutChecks.measure(card, ((width - 32) * activity.resources.displayMetrics.density).toInt())
                    NativeLayoutChecks.visibleTextFits(card)
                }
            } finally { controller.stop().destroy() }
        }
    }
    @Test fun localTrialAloneCannotAdvertiseAnUnconfirmedPlayOffer() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("maguardian_prefs", 0).edit().clear().commit()
        PrefsHelper.initTrial(ctx)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        try {
            MainActivity::class.java.getDeclaredMethod("refreshUI").apply { isAccessible = true }.invoke(controller.get())
            assertEquals(View.GONE, controller.get().findViewById<View>(R.id.tvProtectionTrial).visibility)
            assertTrue(PrefsHelper.isTrialActive(ctx))
        } finally { controller.stop().destroy() }
    }
}
