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
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject
import android.os.Bundle

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

    private fun control(route: String, method: String = "GET"): JSONObject {
        // Fail closed: no control request or oversized rehearsal may use production.
        check(CatalogTestEnvironment.target().packageName == "com.maguardian.security.catalogvalidation")
        check(ThreatCatalogUpdater.URL_CATALOG == "https://10.0.2.2:8443/threat-catalog.json")
        val connection = java.net.URL("https://10.0.2.2:8443/validation/download/$route")
            .openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            assertEquals(200, connection.responseCode)
            return JSONObject(connection.inputStream.use {
                ThreatCatalog.readBounded(it).toString(Charsets.UTF_8)
            })
        } finally {
            connection.disconnect()
        }
    }

    private fun assertNetworkStopsAtLimit(mode: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("network", "online") == "online")
        val armed = control("arm?mode=$mode", "POST")
        val id = armed.getString("id")
        try {
            assertEquals(ThreatCatalog.MAX_BYTES, armed.getInt("maxBytes"))
            assertEquals(8192, armed.getInt("chunkBytes"))
            assertEquals(25, armed.getInt("chunkDelayMs"))
            if (mode == "unknown-length") assertTrue(armed.isNull("advertisedBytes"))
            else assertEquals(1024, armed.getInt("advertisedBytes"))
            // Call the exact HTTPS routine used by update(), not a mock stream or
            // a parser rejection after downloading the whole response.
            try {
                ThreatCatalogUpdater.downloadCatalog()
                fail("Oversized network body was accepted")
            } catch (expected: IllegalArgumentException) {
                assertEquals("Catálogo muito grande", expected.message)
            }
            val deadline = System.currentTimeMillis() + 5000
            var stats = control("stats?id=$id")
            while (!stats.getBoolean("closed") && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
                stats = control("stats?id=$id")
            }
            assertEquals(1, stats.getInt("requests"))
            assertTrue("Peer did not close: $stats", stats.getBoolean("closed"))
            assertTrue("Entire response was sent: $stats", stats.getBoolean("closedEarly"))
            assertFalse(stats.getBoolean("endCalled"))
            val sent = stats.getLong("bytesSent")
            // readBounded retains <= MAX_BYTES and reads at most one extra 8 KiB
            // block. Server writes also include TLS buffering and Android's
            // bounded close-time drain: allow 128 KiB, never the entire 16 MiB.
            assertTrue("No actual over-limit read: $stats", sent > ThreatCatalog.MAX_BYTES)
            assertTrue("Excessive read-ahead: $stats",
                sent <= ThreatCatalog.MAX_BYTES.toLong() + 128 * 1024)
            assertTrue(sent < stats.getLong("totalBytes"))
            Thread.sleep(150)
            assertEquals("Server kept writing after close", sent,
                control("stats?id=$id").getLong("bytesSent"))
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "DOWNLOAD_BOUNDARY_OK $stats\n")
            })
        } finally {
            control("disarm?id=$id", "POST")
        }
    }

    @Test fun oversizedChunkedDownloadWithoutLengthStopsNetworkReading() {
        assertNetworkStopsAtLimit("unknown-length")
    }

    @Test fun oversizedChunkedDownloadWithMisleadingLengthStopsNetworkReading() {
        assertNetworkStopsAtLimit("misleading-length")
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
