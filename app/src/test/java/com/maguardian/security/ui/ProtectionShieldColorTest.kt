package com.maguardian.security.ui

import android.app.Application
import android.app.AppOpsManager
import android.content.Context
import android.widget.ImageView
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
class ProtectionShieldColorTest {
    @Test fun shieldIsGreenOnlyWhenProtectionIsFullyActive() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("maguardian_prefs", 0).edit().clear()
            .putLong("trial_start_date", System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8)).commit()
        for (palette in listOf("original", "petroleo", "gold")) {
            ctx.getSharedPreferences("guardian_appearance", 0).edit().putString("palette", palette).commit()
            PrefsHelper.setProtectionEnabled(ctx, false)
            val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
            val activity = controller.get()
            try {
                val shield = activity.findViewById<ImageView>(R.id.ivShield)
                val appOps = shadowOf(activity.getSystemService(AppOpsManager::class.java))
                val refresh = MainActivity::class.java.getDeclaredMethod("refreshUI").apply { isAccessible = true }
                // Inactive -> active -> paused -> missing permission -> expired -> active.
                for ((subscribed, enabled, permitted) in listOf(
                    Triple(false, false, true), Triple(true, true, true),
                    Triple(true, false, true), Triple(true, true, false),
                    Triple(false, true, true), Triple(true, true, true)
                )) {
                    PrefsHelper.setSubscriptionActive(activity, subscribed)
                    PrefsHelper.setProtectionEnabled(activity, enabled)
                    appOps.setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(),
                        activity.packageName, if (permitted) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED)
                    refresh.invoke(activity)
                    val active = subscribed && enabled && permitted
                    assertEquals("Estado do escudo em $palette", GuardianAppearance.color(activity,
                        if (active) R.color.guardian_success else R.color.guardian_danger),
                        shield.imageTintList!!.defaultColor)
                    assertEquals(if (active) "Desativar" else "Ativar",
                        activity.findViewById<TextView>(R.id.btnToggle).text.toString())
                    assertEquals(enabled, PrefsHelper.isProtectionEnabled(activity))
                    assertEquals(subscribed, PrefsHelper.isSubscriptionActive(activity))
                }
            } finally {
                controller.stop().destroy()
            }
        }
    }
}
