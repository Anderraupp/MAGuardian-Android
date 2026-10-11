package com.maguardian.security.data

import com.maguardian.security.util.PrefsHelper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** Never runs against the owner's installed app or production catalog. */
class CatalogInstalledAppScannerTest {
    private lateinit var ctx: CatalogTestEnvironment.Sandbox
    private val fixture = "com.catalogvalidation.fixture"

    @Before fun setUp() {
        ctx = CatalogTestEnvironment.Sandbox(CatalogTestEnvironment.target())
        CatalogTestEnvironment.resetMemory()
    }

    @After fun tearDown() {
        CatalogTestEnvironment.resetMemory()
        if (::ctx.isInitialized) ctx.close()
    }

    private fun install(version: Int = 1, hash: String? = null, pkg: String = fixture) {
        val actualHash = hash ?: CatalogTestEnvironment.sha(File(
            ctx.packageManager.getApplicationInfo(fixture, 0).sourceDir
        ).readBytes())
        assertTrue(ThreatCatalog.install(ctx, CatalogTestEnvironment.catalog(
            version, CatalogTestEnvironment.indicator(pkg, actualHash)
        )))
    }

    @Test fun newlyPublishedIndicatorDetectsAlreadyInstalledApkWithoutManualScan() {
        assertTrue(ThreatCatalog.install(ctx, CatalogTestEnvironment.catalog(1)))
        assertEquals(0, CatalogInstalledAppScanner.scan(ctx) { fail("Empty catalog") })
        install(2)
        val alerts = mutableListOf<String>()
        val scansBefore = PrefsHelper.getScanCount(ctx)
        val removedBefore = PrefsHelper.getTotalThreatsRemoved(ctx)
        assertEquals(1, CatalogInstalledAppScanner.scan(ctx) { alerts.add(it.packageName) })
        assertEquals(listOf(fixture), alerts)
        assertEquals("detected", PrefsHelper.getThreats(ctx).getJSONObject(0).getString("status"))
        assertEquals(scansBefore, PrefsHelper.getScanCount(ctx))
        assertEquals(removedBefore, PrefsHelper.getTotalThreatsRemoved(ctx))
        assertEquals(0, CatalogInstalledAppScanner.scan(ctx) { fail("Duplicate alert") })
    }

    @Test fun unchangedCatalogCatchesUpWithoutAlertingTwice() {
        install()
        assertEquals(1, CatalogInstalledAppScanner.scan(ctx) {})
        assertFalse(ThreatCatalog.install(ctx, CatalogTestEnvironment.catalog(1,
            CatalogTestEnvironment.indicator(fixture, CatalogTestEnvironment.sha(File(
                ctx.packageManager.getApplicationInfo(fixture, 0).sourceDir
            ).readBytes()))
        )))
        assertEquals(0, CatalogInstalledAppScanner.scan(ctx) { fail("Duplicate") })
    }

    @Test fun wrongHashAndAbsentPackageAreNotDetected() {
        install(hash = "0".repeat(64))
        assertEquals(0, CatalogInstalledAppScanner.scan(ctx) { fail("Wrong SHA-256") })
        install(2, pkg = "com.catalogvalidation.notinstalled")
        assertEquals(0, CatalogInstalledAppScanner.scan(ctx) { fail("Not installed") })
        assertEquals(0, PrefsHelper.getThreats(ctx).length())
    }

    @Test fun keepAndDisabledProtectionRemainRespected() {
        install()
        PrefsHelper.setProtectionEnabled(ctx, false)
        assertEquals(0, CatalogInstalledAppScanner.scan(ctx) { fail("Disabled protection") })
        assertEquals(0, PrefsHelper.getThreats(ctx).length())
        PrefsHelper.setProtectionEnabled(ctx, true)
        PrefsHelper.markThreatKept(ctx, fixture)
        assertEquals(0, CatalogInstalledAppScanner.scan(ctx) { fail("Kept app") })
    }

    @Test fun failedNotificationDoesNotLoseTheDetectionOrRepeatIt() {
        install()
        assertEquals(1, CatalogInstalledAppScanner.scan(ctx) { throw SecurityException("Denied") })
        assertEquals("detected", PrefsHelper.getThreats(ctx).getJSONObject(0).getString("status"))
        assertEquals(0, CatalogInstalledAppScanner.scan(ctx) { fail("Duplicate after denial") })
    }

    @Test fun trustedPackagesRemainExcludedEvenWhenInCatalog() {
        for ((index, pkg) in listOf(
            "io.metamask", "br.com.serasaexperian.consumidor", "br.com.livelo.app",
            "com.santander.app", "br.com.timbrasil.meutim", "br.com.vivo",
            "com.itaucard.activity"
        ).withIndex()) {
            install(index + 1, pkg = pkg)
            assertEquals(0, CatalogInstalledAppScanner.scan(ctx) { fail("Trusted app $pkg") })
        }
        assertEquals(0, PrefsHelper.getThreats(ctx).length())
    }
}
