package com.maguardian.security.data

import androidx.test.platform.app.InstrumentationRegistry
import com.maguardian.security.util.PrefsHelper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real package upgrades. No injected keys, reflection resets or isolated preferences. */
class ThreatCatalogRotationTest {
    @Test fun apkUpgradeRetainsProtectionAndKeepChoices() {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("disposable") == "true")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        check(ctx.packageName == "com.maguardian.security.catalogvalidation")
        val phase = requireNotNull(args.getString("rotationPhase"))
        fun asset(name: String) = ctx.assets.open("rotation-$name.json").bufferedReader().use { it.readText() }
        val policy = JSONObject(asset("policy"))
        val stage = policy.getString("stage")
        val apkVersion = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
        assertEquals(mapOf("old" to 41, "transition" to 42, "recovery" to 43).getValue(stage), apkVersion)
        val oldId = policy.getString("old")
        val nextId = policy.getString("successor")
        val cache = File(ctx.noBackupFilesDir, "threat-catalog.json")
        val fixture = "com.catalogvalidation.fixture"
        val checkpoint = File(ctx.noBackupFilesDir, "rotation-checkpoint")
        val bundled = MalwareDatabase.KNOWN_MALWARE.first {
            !MalwareDatabase.isSystemPrefix(it.packageName) && !MalwareDatabase.isTrustedApp(it.packageName)
        }
        fun keptAndProtected(version: Int) {
            assertEquals(version, ThreatCatalog.snapshot()!!.version)
            assertNotNull("Exact harmless APK hash must still match", ThreatCatalog.match(ctx, fixture))
            assertTrue("Persisted Manter choice must survive the upgrade", PrefsHelper.isThreatKept(ctx, fixture))
            assertNull("Manter must override catalog detection", MalwareDatabase.isMalware(fixture, ctx))
            assertNotNull("Bundled protection must remain active", MalwareDatabase.isMalware(bundled.packageName, ctx))
        }
        fun reject(name: String, message: String) {
            val before = cache.readText()
            val snapshot = ThreatCatalog.snapshot()
            try {
                ThreatCatalog.install(ctx, asset(name))
                fail("Untrusted signing key accepted")
            } catch (e: IllegalArgumentException) {
                assertEquals(message, e.message)
            }
            assertEquals(before, cache.readText())
            assertEquals(snapshot, ThreatCatalog.snapshot())
        }
        when (phase) {
            "old" -> {
                assertEquals("old", stage)
                assertEquals(setOf(oldId), ThreatCatalogKeys.trusted.keys)
                assertFalse(cache.exists())
                ThreatCatalog.initialize(ctx)
                assertEquals(10, ThreatCatalog.snapshot()!!.version)
                assertTrue(ThreatCatalog.install(ctx, asset("old-cache")))
                assertNotNull(MalwareDatabase.isMalware(fixture, ctx))
                PrefsHelper.markThreatKept(ctx, fixture)
                // apply() is asynchronous. Do not kill the instrumentation
                // process until the user's choice has actually reached disk.
                assertTrue(ctx.getSharedPreferences("maguardian_prefs", 0).edit().commit())
                reject("successor", "Chave de assinatura desconhecida")
                assertEquals(asset("old-cache"), cache.readText())
                checkpoint.writeText("old")
                keptAndProtected(11)
            }
            "old-restart" -> {
                assertEquals("old", stage)
                assertEquals("old", checkpoint.readText())
                ThreatCatalog.initialize(ctx)
                assertEquals(asset("old-cache"), cache.readText())
                keptAndProtected(11)
            }
            "transition" -> {
                assertEquals("transition", stage)
                assertEquals(setOf(oldId, nextId), ThreatCatalogKeys.trusted.keys)
                assertFalse(ThreatCatalogKeys.trusted.getValue(oldId).revoked)
                assertEquals("old", checkpoint.readText())
                ThreatCatalog.initialize(ctx)
                keptAndProtected(11)
                assertTrue(ThreatCatalog.install(ctx, asset("successor")))
                assertEquals(asset("successor"), cache.readText())
                checkpoint.writeText("transition")
                keptAndProtected(12)
            }
            "transition-restart" -> {
                assertEquals("transition", stage)
                assertEquals("transition", checkpoint.readText())
                ThreatCatalog.initialize(ctx)
                assertEquals(asset("successor"), cache.readText())
                keptAndProtected(12)
                // Leave a legitimately accepted old-key cache for the recovery APK.
                assertTrue(ThreatCatalog.install(ctx, asset("revoked-cache")))
                checkpoint.writeText("revocation-ready")
                keptAndProtected(13)
            }
            "recovery" -> {
                assertEquals("recovery", stage)
                assertTrue(ThreatCatalogKeys.trusted.getValue(oldId).revoked)
                assertFalse(ThreatCatalogKeys.trusted.getValue(nextId).revoked)
                assertEquals("revocation-ready", checkpoint.readText())
                assertEquals(asset("revoked-cache"), cache.readText())
                ThreatCatalog.initialize(ctx)
                // Cache bytes remain but are NOT used. Bootstrap is signed by B.
                assertEquals(asset("revoked-cache"), cache.readText())
                assertEquals(ThreatCatalog.parse(asset("recovery-bootstrap")), ThreatCatalog.snapshot())
                keptAndProtected(14)
                reject("revoked-update", "Chave de assinatura revogada")
                assertTrue(ThreatCatalog.install(ctx, asset("recovery-update")))
                assertEquals(asset("recovery-update"), cache.readText())
                checkpoint.writeText("recovery")
                keptAndProtected(16)
            }
            "recovery-restart" -> {
                assertEquals("recovery", stage)
                assertEquals("recovery", checkpoint.readText())
                ThreatCatalog.initialize(ctx)
                assertEquals(asset("recovery-update"), cache.readText())
                keptAndProtected(16)
            }
            else -> error("Unknown rotation phase")
        }
        println("ROTATION_OK phase=$phase apk=${ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode} catalog=${ThreatCatalog.snapshot()!!.version} manter=true")
    }
}
