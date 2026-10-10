package com.maguardian.security.data

import android.util.AtomicFile
import com.maguardian.security.util.PrefsHelper
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real Android AtomicFile, PackageManager and preferences; never production storage. */
class ThreatCatalogStorageTest {
    private lateinit var ctx: CatalogTestEnvironment.Sandbox
    private val pkg get() = ctx.packageName
    private val cache get() = File(ctx.noBackupFilesDir, "threat-catalog.json")

    @Before fun setUp() {
        ctx = CatalogTestEnvironment.Sandbox(CatalogTestEnvironment.target())
        CatalogTestEnvironment.resetMemory()
    }
    @After fun tearDown() {
        CatalogTestEnvironment.resetMemory()
        if (::ctx.isInitialized) ctx.close()
    }
    private fun rejected(raw: String) {
        try {
            ThreatCatalog.install(ctx, raw)
            fail("Invalid update accepted")
        } catch (_: IllegalArgumentException) {
        } catch (_: org.json.JSONException) {
        }
    }

    @Test fun invalidOldAndReusedVersionsPreserveDiskAndKeepChoice() {
        PrefsHelper.markThreatKept(ctx, pkg)
        assertTrue(ThreatCatalog.install(ctx, CatalogTestEnvironment.catalog(2)))
        val before = cache.readText()
        rejected("{}")
        rejected(CatalogTestEnvironment.catalog(1))
        rejected(CatalogTestEnvironment.catalog(2,
            CatalogTestEnvironment.indicator(pkg, "0".repeat(64))))
        assertEquals(2, ThreatCatalog.snapshot()!!.version)
        assertEquals(before, cache.readText())
        assertFalse(ThreatCatalog.install(ctx, before))
        assertTrue(PrefsHelper.isThreatKept(ctx, pkg))
        assertTrue(MalwareDatabase.KNOWN_MALWARE.isNotEmpty())
    }

    @Test fun installedApkRequiresExactHashAndHigherVersionCanChangeOrRemoveIndicator() {
        val apk = File(ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir)
        val hash = apk.inputStream().use { stream ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(32768)
            var count = stream.read(buffer)
            while (count != -1) {
                digest.update(buffer, 0, count)
                count = stream.read(buffer)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        ThreatCatalog.install(ctx, CatalogTestEnvironment.catalog(1,
            CatalogTestEnvironment.indicator(pkg, "0".repeat(64))))
        assertNull(ThreatCatalog.match(ctx, pkg))
        ThreatCatalog.install(ctx, CatalogTestEnvironment.catalog(2,
            CatalogTestEnvironment.indicator(pkg, hash)))
        assertEquals("high", ThreatCatalog.match(ctx, pkg)!!.severity)
        assertNull(ThreatCatalog.match(ctx, "$pkg.absent"))
        ThreatCatalog.install(ctx, CatalogTestEnvironment.catalog(3,
            CatalogTestEnvironment.indicator(pkg, hash, "low")))
        assertEquals("low", ThreatCatalog.match(ctx, pkg)!!.severity)
        // Simulate process-memory loss, then read the persisted catalog entirely offline.
        CatalogTestEnvironment.resetMemory()
        ThreatCatalog.initialize(ctx)
        assertEquals("low", ThreatCatalog.match(ctx, pkg)!!.severity)
        ThreatCatalog.install(ctx, CatalogTestEnvironment.catalog(4))
        assertNull(ThreatCatalog.match(ctx, pkg))
        assertEquals(4, ThreatCatalog.snapshot()!!.version)
    }

    @Test fun writeStartFailureDoesNotPublishNewSnapshot() {
        ThreatCatalog.install(ctx, CatalogTestEnvironment.catalog(1))
        val before = cache.readText()
        // A non-empty .new directory makes AtomicFile.startWrite fail on API 30+.
        val obstacle = File(cache.path + ".new").apply { mkdirs() }
        File(obstacle, "blocker").writeText("fixture")
        try {
            try {
                ThreatCatalog.install(ctx, CatalogTestEnvironment.catalog(2))
                fail("Write should fail")
            } catch (_: java.io.IOException) {}
            assertEquals(1, ThreatCatalog.snapshot()!!.version)
            assertEquals(before, cache.readText())
        } finally { obstacle.deleteRecursively() }
    }

    @Test fun interruptedAtomicWriteAndCorruptCacheRetainOfflineFallback() {
        ThreatCatalog.install(ctx, CatalogTestEnvironment.catalog(1))
        val before = cache.readText()
        val atomic = AtomicFile(cache)
        val stream = atomic.startWrite()
        try {
            stream.write("partial".toByteArray())
            throw java.io.IOException("Injected failure after partial write")
        } catch (_: java.io.IOException) {
            atomic.failWrite(stream)
        }
        assertEquals(before, atomic.openRead().bufferedReader().use { it.readText() })
        CatalogTestEnvironment.resetMemory()
        ThreatCatalog.initialize(ctx)
        assertEquals(1, ThreatCatalog.snapshot()!!.version)
        cache.writeText("corrupt")
        CatalogTestEnvironment.resetMemory()
        ThreatCatalog.initialize(ctx)
        assertNull(ThreatCatalog.snapshot())
        assertNotNull(MalwareDatabase.isMalware(MalwareDatabase.KNOWN_MALWARE.first().packageName, ctx))
    }

    @Test fun trustedScanExemptAndKeepRulesSurviveUpdates() {
        val hash = "0".repeat(64)
        val trusted = "com.google.example"
        val bank = "com.nu.production"
        val exempt = "com.ps.pokerstars.fixture"
        val bundled = MalwareDatabase.KNOWN_MALWARE.first().packageName
        ThreatCatalog.install(ctx, CatalogTestEnvironment.catalog(1,
            CatalogTestEnvironment.indicator(trusted, hash),
            CatalogTestEnvironment.indicator(bank, hash),
            CatalogTestEnvironment.indicator(exempt, hash)))
        assertNull(MalwareDatabase.isMalware(trusted, ctx))
        assertNull(MalwareDatabase.isMalware(bank, ctx))
        assertTrue(MalwareDatabase.isScanExempt(exempt))
        assertFalse(MalwareDatabase.getAllPackageNames().contains(trusted))
        PrefsHelper.markThreatKept(ctx, bundled)
        ThreatCatalog.install(ctx, CatalogTestEnvironment.catalog(2))
        CatalogTestEnvironment.resetMemory()
        ThreatCatalog.initialize(ctx)
        assertTrue(PrefsHelper.isThreatKept(ctx, bundled))
        assertNull(MalwareDatabase.isMalware(bundled, ctx))
    }

    @Test fun installationHandlerAndStartupScanUseLocalHashAndRespectKeep() {
        // The instrumentation APK itself is a harmless installed fixture; never fetch an APK.
        val fixture = "$pkg.test"
        val apk = File(ctx.packageManager.getApplicationInfo(fixture, 0).sourceDir)
        val hash = CatalogTestEnvironment.sha(apk.readBytes())
        val receiver = com.maguardian.security.receiver.PackageInstallReceiver()
        val checkInstalled = receiver.javaClass.getDeclaredMethod("checkInstalled",
            android.content.Context::class.java, String::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        ThreatCatalog.install(ctx, CatalogTestEnvironment.catalog(1,
            CatalogTestEnvironment.indicator(fixture, "0".repeat(64))))
        checkInstalled.invoke(receiver, ctx, fixture, false)
        assertEquals(0, PrefsHelper.getThreats(ctx).length())
        ThreatCatalog.install(ctx, CatalogTestEnvironment.catalog(2,
            CatalogTestEnvironment.indicator(fixture, hash)))
        checkInstalled.invoke(receiver, ctx, fixture, false)
        assertEquals(fixture, PrefsHelper.getThreats(ctx).getJSONObject(0).getString("packageName"))

        val service = com.maguardian.security.service.PopupDetectorService()
        android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext",
            android.content.Context::class.java).apply {
            isAccessible = true
            invoke(service, ctx)
        }
        val startup = service.javaClass.getDeclaredMethod("runStartupScan").apply { isAccessible = true }
        startup.invoke(service)
        assertTrue(PrefsHelper.getLastScan(ctx) > 0)
        PrefsHelper.markThreatKept(ctx, fixture)
        val before = PrefsHelper.getThreats(ctx).toString()
        val found = PrefsHelper.getTotalThreatsFound(ctx)
        receiver.onReceive(ctx, android.content.Intent(android.content.Intent.ACTION_PACKAGE_REPLACED)
            .setData(android.net.Uri.parse("package:$fixture")))
        service.javaClass.getDeclaredMethod("onThreatDetected", MalwareDatabase.MalwareEntry::class.java)
            .apply { isAccessible = true }.invoke(service, ThreatCatalog.match(ctx, fixture)!!)
        startup.invoke(service)
        assertEquals(before, PrefsHelper.getThreats(ctx).toString())
        assertEquals(found, PrefsHelper.getTotalThreatsFound(ctx))
    }

    @Test fun writeFailurePreservesThePreviousAtomicFile() {
        ThreatCatalog.install(ctx, CatalogTestEnvironment.catalog(1))
        val before = cache.readText()
        // /dev/full throws ENOSPC on write, exercising install's failWrite path.
        val full = File("/dev/full")
        org.junit.Assume.assumeTrue("Emulator must provide /dev/full", full.exists() && full.canWrite())
        android.system.Os.symlink(full.path, cache.path + ".new")
        try {
            try {
                ThreatCatalog.install(ctx, CatalogTestEnvironment.catalog(2))
                fail("Write should fail")
            } catch (_: java.io.IOException) {}
            assertEquals(1, ThreatCatalog.snapshot()!!.version)
            assertEquals(before, cache.readText())
        } finally { File(cache.path + ".new").delete() }
    }
}
