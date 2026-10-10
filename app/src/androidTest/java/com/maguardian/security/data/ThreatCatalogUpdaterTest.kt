package com.maguardian.security.data

import android.app.job.JobInfo
import android.app.job.JobScheduler
import androidx.test.platform.app.InstrumentationRegistry
import com.maguardian.security.util.PrefsHelper
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ThreatCatalogUpdaterTest {
    private lateinit var ctx: CatalogTestEnvironment.Sandbox

    @Before fun setUp() {
        ctx = CatalogTestEnvironment.Sandbox(CatalogTestEnvironment.target())
        CatalogTestEnvironment.resetMemory()
    }
    @After fun tearDown() {
        CatalogTestEnvironment.resetMemory()
        if (::ctx.isInitialized) ctx.close()
    }
    private fun update(manual: Boolean): String {
        val finished = CountDownLatch(1)
        var result = ""
        ThreatCatalogUpdater.update(ctx, manual) { result = it; finished.countDown() }
        assertTrue("Bounded download did not finish", finished.await(60, TimeUnit.SECONDS))
        return result
    }

    @Test fun liveGithubMetadataWithoutQaAuthorityCannotReplaceSignedFixture() {
        val rawUrl = "https://raw.githubusercontent.com/Anderraupp/MAGuardian-Android/main/threat-catalog/catalog.json"
        val connection = java.net.URL(rawUrl).openConnection() as javax.net.ssl.HttpsURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        val raw = try {
            assertEquals(200, connection.responseCode)
            connection.inputStream.use { ThreatCatalog.readBounded(it) }.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
        ThreatCatalog.install(ctx, CatalogTestEnvironment.catalog(2))
        val before = File(ctx.noBackupFilesDir, "threat-catalog.json").readText()
        val snapshot = ThreatCatalog.snapshot()
        try {
            ThreatCatalog.install(ctx, raw)
            fail("Metadata without the embedded QA signing authority was accepted")
        } catch (_: IllegalArgumentException) {
        } catch (_: org.json.JSONException) {
        }
        assertEquals(snapshot, ThreatCatalog.snapshot())
        assertEquals(before, File(ctx.noBackupFilesDir, "threat-catalog.json").readText())
    }

    @Test fun manualUpdateDownloadsOnlyMetadataAndBypassesAutomaticCooldown() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("network", "online") == "online")
        PrefsHelper.setCatalogAttempt(ctx, System.currentTimeMillis())
        assertEquals(PrefsHelper.getCatalogStatus(ctx), update(false))
        assertNull(ThreatCatalog.snapshot())
        val status = update(true)
        assertFalse(status, status.startsWith("Falha"))
        assertTrue(PrefsHelper.getCatalogSuccess(ctx) > 0)
        val cache = File(ctx.noBackupFilesDir, "threat-catalog.json")
        assertTrue(cache.length() in 1..ThreatCatalog.MAX_BYTES.toLong())
        assertNotNull(ThreatCatalog.parse(cache.readText()))
        assertEquals(setOf("threat-catalog.json"), ctx.directory.list()!!.toSet())
        assertEquals("A base já está atualizada.", update(true))
    }

    @Test fun unavailableNetworkPreservesOfflineCatalogPreferencesAndBundledDetection() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("network") == "offline")
        val bundled = MalwareDatabase.KNOWN_MALWARE.first().packageName
        PrefsHelper.markThreatKept(ctx, "com.example.kept")
        ThreatCatalog.install(ctx, CatalogTestEnvironment.catalog(10))
        val cache = File(ctx.noBackupFilesDir, "threat-catalog.json")
        val before = cache.readText()
        assertTrue(update(true).startsWith("Falha"))
        assertEquals(before, cache.readText())
        assertEquals(10, ThreatCatalog.snapshot()!!.version)
        assertEquals(0, PrefsHelper.getCatalogSuccess(ctx))
        assertTrue(PrefsHelper.isThreatKept(ctx, "com.example.kept"))
        assertNotNull(MalwareDatabase.isMalware(bundled, ctx))
    }

    @Test fun dailyJobIsPersistentNetworkConstrainedAndSchedulingIsIdempotent() {
        val scheduler = ctx.getSystemService(JobScheduler::class.java)
        try {
            ThreatCatalogUpdater.schedule(ctx)
            val first = scheduler.allPendingJobs.single {
                it.service.className == ThreatCatalogJobService::class.java.name
            }
            assertTrue(first.isPeriodic)
            assertTrue(first.isPersisted)
            assertEquals(TimeUnit.DAYS.toMillis(1), first.intervalMillis)
            assertEquals(JobInfo.NETWORK_TYPE_ANY, first.networkType)
            ThreatCatalogUpdater.schedule(ctx)
            assertEquals(1, scheduler.allPendingJobs.count { it.id == first.id })
        } finally {
            scheduler.allPendingJobs.filter {
                it.service.className == ThreatCatalogJobService::class.java.name
            }.forEach { scheduler.cancel(it.id) }
        }
    }

    @Test fun jobSchedulerActuallyRunsTheUpdater() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("network", "online") == "online")
        // JobService uses its real application context, still the disposable package.
        val target = CatalogTestEnvironment.target()
        val scheduler = target.getSystemService(JobScheduler::class.java)
        val prefs = target.getSharedPreferences("maguardian_prefs", 0)
        val previous = prefs.all
        try {
            assertTrue(prefs.edit().remove("catalog_attempt").remove("catalog_success").commit())
            ThreatCatalogUpdater.schedule(target)
            val job = scheduler.allPendingJobs.single {
                it.service.className == ThreatCatalogJobService::class.java.name
            }
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("cmd jobscheduler run -f ${target.packageName} ${job.id}")
            val response = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                .bufferedReader().use { it.readText() }
            assertTrue(response, response.contains("Running job"))
            val deadline = System.currentTimeMillis() + 60000
            while (PrefsHelper.getCatalogSuccess(target) == 0L && System.currentTimeMillis() < deadline) {
                Thread.sleep(200)
            }
            assertTrue(PrefsHelper.getCatalogStatus(target), PrefsHelper.getCatalogSuccess(target) > 0L)
        } finally {
            scheduler.allPendingJobs.filter {
                it.service.className == ThreatCatalogJobService::class.java.name
            }.forEach { scheduler.cancel(it.id) }
            // Remove only updater metadata, not preferences owned by other scenarios.
            val edit = prefs.edit()
            for (key in listOf("catalog_attempt", "catalog_success", "catalog_status")) {
                when (val value = previous[key]) {
                    is Long -> edit.putLong(key, value)
                    is String -> edit.putString(key, value)
                    else -> edit.remove(key)
                }
            }
            assertTrue(edit.commit())
            File(target.noBackupFilesDir, "threat-catalog.json").delete()
        }
    }
}
