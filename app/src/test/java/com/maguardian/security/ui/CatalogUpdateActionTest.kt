package com.maguardian.security.ui

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.maguardian.security.R
import com.maguardian.security.data.ThreatCatalog
import com.maguardian.security.data.ThreatCatalogUpdater
import com.maguardian.security.util.PrefsHelper
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CatalogUpdateActionTest {
    @Test fun manualCheckWithoutPendingVersionShowsProgressAndFailureAndAllowsRetry() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("maguardian_prefs", 0).edit().clear().commit()
        ThreatCatalog.initialize(ctx)
        val base = ThreatCatalog.snapshot()
        assertNotNull("A base embarcada deve permanecer disponível", base)
        PrefsHelper.setCatalogAvailableVersion(ctx, 0)
        PrefsHelper.setCatalogSuccess(ctx, 1000L)
        PrefsHelper.setCatalogStatus(ctx, "A base já está atualizada.")
        assertFalse(ThreatCatalogUpdater.hasPendingUpdate(ctx))
        // Robolectric has no active network: exercise the real updater's failure path.
        for (palette in listOf("original", "petroleo", "gold")) {
            ctx.getSharedPreferences("guardian_appearance", 0).edit().putString("palette", palette).commit()
            val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
            try {
                val activity = controller.get()
                GuardianNavigation.selectMain(activity, true)
                val button = activity.findViewById<Button>(R.id.btnUpdateCatalog)
                val feedback = activity.findViewById<TextView>(R.id.tvCatalogUpdateFeedback)
                repeat(2) {
                    assertTrue("Consulta manual disponível sem nova versão conhecida", button.isEnabled)
                    // Manual requests must not be skipped by the automatic-check interval.
                    PrefsHelper.setCatalogAttempt(ctx, System.currentTimeMillis())
                    assertTrue(button.performClick())
                    assertFalse(button.isEnabled)
                    assertEquals(View.VISIBLE, feedback.visibility)
                    assertEquals("Verificando atualização...", feedback.text.toString())
                    val deadline = System.nanoTime() + 5_000_000_000L
                    while ((!button.isEnabled || ThreatCatalogUpdater.isUpdating()) && System.nanoTime() < deadline) {
                        shadowOf(Looper.getMainLooper()).idle()
                        Thread.sleep(10)
                    }
                    assertFalse(ThreatCatalogUpdater.isUpdating())
                    assertTrue(button.isEnabled)
                    assertEquals("Falha na atualização. A última base válida e a base embarcada foram preservadas.",
                        feedback.text.toString())
                    assertEquals(feedback.text.toString(), ShadowToast.getTextOfLatestToast())
                    assertSame(base, ThreatCatalog.snapshot())
                    assertEquals(1000L, PrefsHelper.getCatalogSuccess(ctx))
                }
            } finally { controller.stop().destroy() }
        }
    }

    @Test fun runningUpdateIsExplainedAndDoesNotAllowConcurrentManualRequests() {
        val running = ThreatCatalogUpdater::class.java.getDeclaredField("running").apply { isAccessible = true }
            .get(null) as AtomicBoolean
        running.set(true)
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        try {
            val activity = controller.create().start().get()
            val button = activity.findViewById<Button>(R.id.btnUpdateCatalog)
            val feedback = activity.findViewById<TextView>(R.id.tvCatalogUpdateFeedback)
            assertFalse(button.isEnabled)
            assertEquals(View.VISIBLE, feedback.visibility)
            assertEquals("Verificando atualização...", feedback.text.toString())
            running.set(false)
            PrefsHelper.setCatalogStatus(activity, "A base já está atualizada.")
            MainActivity::class.java.getDeclaredMethod("showCatalogStatus").apply { isAccessible = true }.invoke(activity)
            assertTrue(button.isEnabled)
            assertEquals("A base já está atualizada.", feedback.text.toString())
        } finally {
            running.set(false)
            controller.stop().destroy()
        }
    }
}
