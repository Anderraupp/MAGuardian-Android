package com.maguardian.security.ui

import android.app.Application
import android.view.View
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
class WelcomeFlowTest {
    @Test fun startUsesExistingHomeAndNeverUnlocksPremium() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("guardian_appearance", 0).edit().clear().commit()
        PrefsHelper.setSubscriptionActive(ctx, false)
        val controller = Robolectric.buildActivity(WelcomeActivity::class.java).create().start()
        try {
            controller.get().findViewById<View>(R.id.btnWelcomeStart).performClick()
            assertEquals(MainActivity::class.java.name, shadowOf(controller.get()).nextStartedActivity.component?.className)
            assertTrue(ctx.getSharedPreferences("guardian_appearance", 0).getBoolean("welcome_seen", false))
            assertFalse(PrefsHelper.isSubscriptionActive(ctx))
        } finally { controller.stop().destroy() }
    }
    @Test fun existingSubscriberActionRequestsRealRestorationWithoutGrantingAccess() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("guardian_appearance", 0).edit().clear().commit()
        PrefsHelper.setSubscriptionActive(ctx, false)
        val controller = Robolectric.buildActivity(WelcomeActivity::class.java).create().start()
        try {
            controller.get().findViewById<View>(R.id.btnWelcomeRestore).performClick()
            val intent = shadowOf(controller.get()).nextStartedActivityForResult.intent
            assertEquals(SubscriptionActivity::class.java.name, intent.component?.className)
            assertTrue(intent.getBooleanExtra("guardian_restore", false))
            assertFalse(PrefsHelper.isSubscriptionActive(ctx))
        } finally { controller.stop().destroy() }
    }
}
