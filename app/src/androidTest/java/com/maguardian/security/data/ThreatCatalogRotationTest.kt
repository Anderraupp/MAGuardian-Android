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
        val route = args.getString("rotationRoute") ?: "three-step"
        check(route in setOf("three-step", "direct"))
        val direct = route == "direct"
        check(!direct || phase in setOf("old", "old-restart", "recovery", "recovery-restart"))
        val oldCache = if (direct) "direct-old-cache" else "old-cache"
        val oldVersion = if (direct) 13 else 11
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
                assertTrue(ThreatCatalog.install(ctx, asset(oldCache)))
                assertNotNull(MalwareDatabase.isMalware(fixture, ctx))
                PrefsHelper.markThreatKept(ctx, fixture)
                // apply() is asynchronous. Do not kill the instrumentation
                // process until the user's choice has actually reached disk.
                assertTrue(ctx.getSharedPreferences("maguardian_prefs", 0).edit().commit())
                reject("successor", "Chave de assinatura desconhecida")
                assertEquals(asset(oldCache), cache.readText())
                checkpoint.writeText(if (direct) "direct-old" else "old")
                keptAndProtected(oldVersion)
            }
            "old-restart" -> {
                assertEquals("old", stage)
                assertEquals(if (direct) "direct-old" else "old", checkpoint.readText())
                ThreatCatalog.initialize(ctx)
                assertEquals(asset(oldCache), cache.readText())
                keptAndProtected(oldVersion)
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
                assertEquals(if (direct) "direct-old" else "revocation-ready", checkpoint.readText())
                val rejectedCache = asset(if (direct) "direct-old-cache" else "revoked-cache")
                assertEquals(rejectedCache, cache.readText())
                try {
                    ThreatCatalog.parse(cache.readText())
                    fail("Revoked persisted cache accepted")
                } catch (e: IllegalArgumentException) {
                    assertEquals("Chave de assinatura revogada", e.message)
                }
                ThreatCatalog.initialize(ctx)
                // Cache bytes remain but are NOT used. Bootstrap is signed by B.
                assertEquals(rejectedCache, cache.readText())
                assertEquals(nextId, JSONObject(asset("recovery-bootstrap")).getString("keyId"))
                assertEquals(ThreatCatalog.parse(asset("recovery-bootstrap")), ThreatCatalog.snapshot())
                keptAndProtected(14)
                reject("revoked-update", "Chave de assinatura revogada")
                assertTrue(ThreatCatalog.install(ctx, asset("recovery-update")))
                assertEquals(asset("recovery-update"), cache.readText())
                checkpoint.writeText(if (direct) "direct-recovery" else "recovery")
                keptAndProtected(16)
            }
            "recovery-restart" -> {
                assertEquals("recovery", stage)
                assertEquals(if (direct) "direct-recovery" else "recovery", checkpoint.readText())
                ThreatCatalog.initialize(ctx)
                assertEquals(asset("recovery-update"), cache.readText())
                keptAndProtected(16)
            }
            else -> error("Unknown rotation phase")
        }
        println("ROTATION_OK route=$route phase=$phase apk=${ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode} catalog=${ThreatCatalog.snapshot()!!.version} manter=true")
    }
}
