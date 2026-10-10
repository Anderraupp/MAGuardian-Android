package com.maguardian.security.data

import androidx.test.platform.app.InstrumentationRegistry
import com.maguardian.security.util.PrefsHelper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/** Real package upgrades. No injected keys, reflection resets or isolated preferences. */
class ThreatCatalogRotationTest {
    @Test fun apkUpgradeRetainsProtectionAndKeepChoices() {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("disposable") == "true")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        check(ctx.packageName == "com.maguardian.security.catalogvalidation")
        val phase = requireNotNull(args.getString("rotationPhase"))
        val route = args.getString("rotationRoute") ?: "three-step"
        check(route in setOf("three-step", "direct", "interrupted", "corrupt-truncated", "corrupt-tampered"))
        val direct = route != "three-step"
        val interrupted = route == "interrupted"
        val corrupt = route.startsWith("corrupt-")
        val damagedAsset = "$route-cache"
        val directPhases = setOf("old", "old-restart", "recovery", "recovery-restart")
        val interruptedPhases = setOf("interrupted-staged", "interrupted-abandoned")
        check(!direct || phase in directPhases || (interrupted && phase in interruptedPhases) ||
            (corrupt && phase == "corrupt-prepare"))
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
            "old-restart", "interrupted-staged", "interrupted-abandoned" -> {
                assertEquals("old", stage)
                if (phase in interruptedPhases) {
                    assertTrue(interrupted)
                    assertEquals(41, apkVersion)
                    assertEquals(setOf(oldId), ThreatCatalogKeys.trusted.keys)
                }
                assertEquals(if (direct) "direct-old" else "old", checkpoint.readText())
                ThreatCatalog.initialize(ctx)
                assertEquals(asset(oldCache), cache.readText())
                keptAndProtected(oldVersion)
                if (phase in interruptedPhases) {
                    // No re-seeding cache, checkpoints or preferences: checks
                    // must observe what was persisted before installation.
                    reject("successor", "Chave de assinatura desconhecida")
                    assertEquals("direct-old", checkpoint.readText())
                    keptAndProtected(13)
                }
            }
            "corrupt-prepare" -> {
                assertTrue(corrupt)
                assertEquals("old", stage)
                assertEquals("direct-old", checkpoint.readText())
                assertEquals(asset(oldCache), cache.readText())
                ThreatCatalog.initialize(ctx)
                keptAndProtected(13)
                val damaged = asset(damagedAsset)
                // Prove corruption under the STILL ACTIVE old key, before the
                // recovery APK revokes it. Tampered checksum alone is valid.
                try {
                    ThreatCatalog.parse(damaged)
                    fail("Damaged cache fixture was accepted")
                } catch (e: Exception) {
                    if (route == "corrupt-truncated") {
                        assertTrue("Truncated JSON must fail parsing", e is org.json.JSONException)
                    } else {
                        assertTrue(e is IllegalArgumentException)
                        assertEquals("Assinatura do catálogo inválida", e.message)
                    }
                }
                FileOutputStream(cache).use {
                    it.write(damaged.toByteArray(Charsets.UTF_8))
                    it.fd.sync()
                }
                assertEquals(damaged, cache.readText())
                checkpoint.writeText("$route-ready")
                // In-process protection remains based on the last good snapshot.
                keptAndProtected(13)
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
                assertEquals(if (corrupt) "$route-ready" else if (direct) "direct-old" else "revocation-ready",
                    checkpoint.readText())
                val rejectedCache = asset(if (corrupt) damagedAsset else if (direct) "direct-old-cache" else "revoked-cache")
                assertEquals(rejectedCache, cache.readText())
                try {
                    ThreatCatalog.parse(cache.readText())
                    fail("Revoked persisted cache accepted")
                } catch (e: Exception) {
                    if (route == "corrupt-truncated") {
                        assertTrue("Truncated JSON must not become a snapshot", e is org.json.JSONException)
                    } else {
                        assertTrue(e is IllegalArgumentException)
                        assertEquals("Chave de assinatura revogada", e.message)
                    }
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
