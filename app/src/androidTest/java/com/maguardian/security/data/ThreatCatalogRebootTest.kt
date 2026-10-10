package com.maguardian.security.data

import android.app.job.JobScheduler
import androidx.test.platform.app.InstrumentationRegistry
import com.maguardian.security.util.PrefsHelper
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Two explicitly selected invocations, separated by a real emulator reboot. */
class ThreatCatalogRebootTest {
    @Test fun prepareOrVerifyPersistedOfflineState() {
        val phase = InstrumentationRegistry.getArguments().getString("rebootPhase")
        assumeTrue(phase == "prepare" || phase == "verify")
        val ctx = CatalogTestEnvironment.target()
        CatalogTestEnvironment.resetMemory()
        if (phase == "prepare") {
            // Boot must not launch paid protection/foreground phone services in this fixture.
            PrefsHelper.setProtectionEnabled(ctx, false)
            val apk = File(ctx.packageManager.getApplicationInfo(ctx.packageName, 0).sourceDir)
            val hash = CatalogTestEnvironment.sha(apk.readBytes())
            ThreatCatalog.install(ctx, CatalogTestEnvironment.catalog(101,
                CatalogTestEnvironment.indicator(ctx.packageName, hash)))
            PrefsHelper.markThreatKept(ctx, "com.example.kept")
            PrefsHelper.setCatalogAttempt(ctx, System.currentTimeMillis())
            ThreatCatalogUpdater.schedule(ctx)
            // Force preference durability before the host reboots the emulator.
            assertTrue(ctx.getSharedPreferences("maguardian_prefs", 0).edit().commit())
        } else {
            ThreatCatalog.initialize(ctx)
            assertEquals(101, ThreatCatalog.snapshot()!!.version)
            assertNotNull(ThreatCatalog.match(ctx, ctx.packageName))
            assertTrue(PrefsHelper.isThreatKept(ctx, "com.example.kept"))
            val job = ctx.getSystemService(JobScheduler::class.java).allPendingJobs.single {
                it.service.className == ThreatCatalogJobService::class.java.name
            }
            assertTrue(job.isPersisted)
            assertEquals(java.util.concurrent.TimeUnit.DAYS.toMillis(1), job.intervalMillis)
        }
    }
}
