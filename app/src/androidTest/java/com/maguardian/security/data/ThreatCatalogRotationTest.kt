package com.maguardian.security.data

import android.os.Process
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import com.maguardian.security.util.PrefsHelper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/** Real QA persistence across processes. No reflection resets or isolated preferences. */
class ThreatCatalogRotationTest {
    @Test fun apkUpgradeRetainsProtectionAndKeepChoices() {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("disposable") == "true")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        check(ctx.packageName == "com.maguardian.security.catalogvalidation")
        val phase = requireNotNull(args.getString("rotationPhase"))
        val route = args.getString("rotationRoute") ?: "three-step"
        check(route in setOf("three-step", "direct", "interrupted", "corrupt-truncated", "corrupt-tampered", "atomic-pending", "oversized"))
        val atomicPending = route == "atomic-pending"
        if (atomicPending) {
            check(android.os.Build.VERSION.SDK_INT >= 30) { "This rehearsal requires AtomicFile .new semantics" }
            check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator only" }
            check(phase in setOf("old", "old-restart", "atomic-write", "atomic-verify", "atomic-restart"))
        }
        val oversized = route == "oversized"
        val direct = route in setOf("direct", "interrupted", "corrupt-truncated", "corrupt-tampered", "oversized")
        val interrupted = route == "interrupted"
        val corrupt = route.startsWith("corrupt-")
        val damagedAsset = "$route-cache"
        val directPhases = setOf("old", "old-restart", "recovery", "recovery-restart")
        val interruptedPhases = setOf("interrupted-staged", "interrupted-abandoned")
        check(!direct || phase in directPhases || (interrupted && phase in interruptedPhases) ||
            (corrupt && phase == "corrupt-prepare") || (oversized && phase == "oversized-prepare"))
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
        val pending = File(cache.path + ".new")
        val atomicCheckpoint = File(ctx.noBackupFilesDir, "atomic-pending-ready.json")
        val preferences = File(ctx.applicationInfo.dataDir, "shared_prefs/maguardian_prefs.xml")
        val oversizedCheckpoint = File(ctx.noBackupFilesDir, "oversized-evidence.json")
        val partial = "{\"format\":2,\"payload\":\"unfinished".toByteArray(Charsets.UTF_8)
        fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        fun fileSha(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(8192)
                var count = stream.read(buffer)
                while (count != -1) {
                    digest.update(buffer, 0, count)
                    count = stream.read(buffer)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
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
            "oversized-prepare" -> {
                assertTrue(oversized)
                assertEquals("old", stage)
                assertEquals("direct-old", checkpoint.readText())
                ThreatCatalog.initialize(ctx)
                keptAndProtected(13)
                val original = asset("direct-old-cache").toByteArray(Charsets.UTF_8)
                assertArrayEquals(original, cache.readBytes())
                val size = policy.getLong("oversizedCacheBytes")
                assertTrue(size > ThreatCatalog.MAX_BYTES)
                // Valid old-key envelope padded with legal JSON whitespace.
                // Size is the only rejection reason under the old QA policy.
                assertEquals(13, ThreatCatalog.parse(String(original, Charsets.UTF_8)).version)
                FileOutputStream(cache).use { stream ->
                    stream.write(original)
                    val spaces = ByteArray(8192) { 32 }
                    var remaining = size - original.size
                    while (remaining > 0) {
                        val count = minOf(remaining, spaces.size.toLong()).toInt()
                        stream.write(spaces, 0, count)
                        remaining -= count
                    }
                    stream.fd.sync()
                }
                assertEquals(size, cache.length())
                val evidence = JSONObject().put("bytes", size).put("maxBytes", ThreatCatalog.MAX_BYTES)
                    .put("sha256", fileSha(cache)).put("prefsSha256", fileSha(preferences))
                    .put("prefsModified", preferences.lastModified()).put("preparePid", Process.myPid())
                FileOutputStream(oversizedCheckpoint).use {
                    it.write(evidence.toString().toByteArray(Charsets.UTF_8))
                    it.fd.sync()
                }
                checkpoint.writeText("oversized-ready")
                keptAndProtected(13)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "OVERSIZED_PREPARED $evidence\n")
                })
            }
            "atomic-write" -> {
                assertTrue(atomicPending)
                assertEquals("old", stage)
                assertEquals("old", checkpoint.readText())
                assertFalse(atomicCheckpoint.exists())
                assertFalse(pending.exists())
                assertFalse(File(cache.path + ".bak").exists())
                ThreatCatalog.initialize(ctx)
                keptAndProtected(11)
                val original = asset("old-cache").toByteArray(Charsets.UTF_8)
                assertArrayEquals(original, cache.readBytes())
                val prefsBefore = preferences.readBytes()
                val prefsModified = preferences.lastModified()
                // Deliberately NOT wrapped in use/try/finally. The controller
                // kills this QA process while the stream is still open, without
                // either failWrite (already covered by StorageTest) or finishWrite.
                val stream = AtomicFile(cache).startWrite()
                stream.write(partial)
                stream.fd.sync()
                assertArrayEquals(partial, pending.readBytes())
                assertArrayEquals(original, cache.readBytes())
                assertArrayEquals(prefsBefore, preferences.readBytes())
                val evidence = JSONObject().put("pid", Process.myPid())
                    .put("baseSha256", sha(original)).put("pendingSha256", sha(partial))
                    .put("prefsSha256", sha(prefsBefore)).put("prefsModified", prefsModified)
                FileOutputStream(atomicCheckpoint).use {
                    it.write(evidence.toString().toByteArray(Charsets.UTF_8))
                    it.fd.sync()
                }
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "ATOMIC_PENDING_READY pid=${Process.myPid()} base=${sha(original)}\n")
                })
                // Bounded wait, never a successful test return. Keep the fd live.
                Thread.sleep(180_000)
                stream.write(0)
                fail("Controller did not terminate the QA writer before commit")
            }
            "atomic-verify", "atomic-restart" -> {
                assertTrue(atomicPending)
                assertEquals("old", stage)
                assertEquals("old", checkpoint.readText())
                val evidence = JSONObject(atomicCheckpoint.readText())
                assertNotEquals("Must verify in a different process", evidence.getInt("pid"), Process.myPid())
                assertNull("Must load from disk, not reset process memory", ThreatCatalog.snapshot())
                val original = asset("old-cache").toByteArray(Charsets.UTF_8)
                assertArrayEquals(original, cache.readBytes())
                assertEquals(evidence.getString("baseSha256"), sha(cache.readBytes()))
                assertEquals(evidence.getString("prefsSha256"), sha(preferences.readBytes()))
                assertEquals(evidence.getLong("prefsModified"), preferences.lastModified())
                if (phase == "atomic-verify") {
                    assertTrue("Killed writer must leave the incomplete .new", pending.isFile)
                    assertArrayEquals(partial, pending.readBytes())
                    assertEquals(evidence.getString("pendingSha256"), sha(pending.readBytes()))
                } else {
                    assertFalse("Discarded .new must not reappear", pending.exists())
                }
                ThreatCatalog.initialize(ctx)
                assertEquals(ThreatCatalog.parse(asset("old-cache")), ThreatCatalog.snapshot())
                keptAndProtected(11)
                assertArrayEquals(original, cache.readBytes())
                assertEquals(evidence.getString("baseSha256"), sha(cache.readBytes()))
                assertFalse("AtomicFile.openRead must discard uncommitted .new", pending.exists())
                assertFalse(File(cache.path + ".bak").exists())
                // Read-only verification: never markThreatKept/edit/commit here.
                assertEquals(evidence.getString("prefsSha256"), sha(preferences.readBytes()))
                assertEquals(evidence.getLong("prefsModified"), preferences.lastModified())
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "ATOMIC_PENDING_OK phase=$phase pid=${Process.myPid()} base=${sha(original)} pending=absent detection=true manter=true prefsUnchanged=true\n")
                })
            }
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
                if (oversized) {
                    verifyOversizedRecovery(ctx, cache, preferences, oversizedCheckpoint, checkpoint,
                        policy, stage, phase, nextId, ::asset, ::fileSha, ::keptAndProtected)
                } else {
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
            }
            "recovery-restart" -> {
                assertEquals("recovery", stage)
                if (oversized) {
                    verifyOversizedRecovery(ctx, cache, preferences, oversizedCheckpoint, checkpoint,
                        policy, stage, phase, nextId, ::asset, ::fileSha, ::keptAndProtected)
                } else {
                assertEquals(if (direct) "direct-recovery" else "recovery", checkpoint.readText())
                ThreatCatalog.initialize(ctx)
                assertEquals(asset("recovery-update"), cache.readText())
                keptAndProtected(16)
                }
            }
            else -> error("Unknown rotation phase")
        }
        println("ROTATION_OK route=$route phase=$phase apk=${ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode} catalog=${ThreatCatalog.snapshot()!!.version} manter=true")
    }

    private fun verifyOversizedRecovery(
        ctx: android.content.Context, cache: File, preferences: File, evidenceFile: File,
        checkpoint: File, policy: JSONObject, stage: String, phase: String, nextId: String,
        asset: (String) -> String, fileSha: (File) -> String, keptAndProtected: (Int) -> Unit
    ) {
        assertEquals("recovery", stage)
        assertEquals("oversized-ready", checkpoint.readText())
        val evidence = JSONObject(evidenceFile.readText())
        assertNotEquals(evidence.getInt("preparePid"), Process.myPid())
        assertEquals(policy.getLong("oversizedCacheBytes"), cache.length())
        assertEquals(evidence.getLong("bytes"), cache.length())
        assertTrue(cache.length() > ThreatCatalog.MAX_BYTES)
        assertEquals(evidence.getString("sha256"), fileSha(cache))
        assertEquals(evidence.getString("prefsSha256"), fileSha(preferences))
        assertNull("Real fresh-process initialization, no snapshot reset", ThreatCatalog.snapshot())
        val started = android.os.SystemClock.elapsedRealtime()
        ThreatCatalog.initialize(ctx)
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        assertTrue("Initialization must finish within 10 seconds, took $elapsed ms", elapsed < 10_000)
        assertEquals(nextId, JSONObject(asset("recovery-bootstrap")).getString("keyId"))
        assertEquals(ThreatCatalog.parse(asset("recovery-bootstrap")), ThreatCatalog.snapshot())
        keptAndProtected(14)
        // Observe the production bounded reader on the exact persisted file.
        // Never allocate/readText/readBytes the entire oversized cache.
        var bytesRead = 0L
        cache.inputStream().use { input ->
            val counted = object : java.io.FilterInputStream(input) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = `in`.read(b, off, len)
                    if (n > 0) bytesRead += n
                    return n
                }
            }
            try {
                ThreatCatalog.readBounded(counted)
                fail("Oversized persisted cache accepted")
            } catch (e: IllegalArgumentException) {
                assertEquals("Catálogo muito grande", e.message)
            }
        }
        assertTrue(bytesRead > ThreatCatalog.MAX_BYTES)
        assertTrue(bytesRead <= ThreatCatalog.MAX_BYTES + 8192L)
        assertTrue("Must stop before reading the whole file", bytesRead < cache.length())
        assertEquals(evidence.getString("sha256"), fileSha(cache))
        // Read-only verification: no preference edits to hide upgrade data loss.
        assertEquals(evidence.getString("prefsSha256"), fileSha(preferences))
        assertEquals(evidence.getLong("prefsModified"), preferences.lastModified())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("stream", "OVERSIZED_OK phase=$phase bytes=${cache.length()} sha256=${fileSha(cache)} readBytes=$bytesRead initMs=$elapsed bootstrap=14 detection=true manter=true prefsUnchanged=true\n")
        })
    }
}
