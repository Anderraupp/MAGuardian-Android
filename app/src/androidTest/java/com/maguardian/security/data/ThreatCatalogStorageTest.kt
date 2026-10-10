package com.maguardian.security.data

import android.util.AtomicFile
import com.maguardian.security.util.PrefsHelper
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

/** Real Android AtomicFile, PackageManager and preferences; never production storage. */
class ThreatCatalogStorageTest {
    private lateinit var ctx: CatalogTestEnvironment.Sandbox
    private val pkg get() = ctx.packageName
    private val cache get() = File(ctx.noBackupFilesDir, "threat-catalog.json")

    @Before fun setUp() {
        val args = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        val target = if (args.getString("rotationRoute") in setOf("concurrent", "size-boundary")) {
            check(args.getString("disposable") == "true")
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.also {
                check(it.packageName == "com.maguardian.security.catalogvalidation")
            }
        } else CatalogTestEnvironment.target()
        // Rotation APK pins are immutable: use their already-signed assets,
        // never inject the ordinary storage suite's mutable QA signing key.
        ctx = CatalogTestEnvironment.Sandbox(target)
        CatalogTestEnvironment.resetMemory()
    }
    @After fun tearDown() {
        CatalogTestEnvironment.resetMemory()
        if (::ctx.isInitialized) ctx.close()
    }
    @Test fun concurrentLowerFirstKeepsTheHigherSignedCatalog() = concurrentInstall(22)
    @Test fun concurrentHigherFirstRejectsTheOlderSignedCatalog() = concurrentInstall(23)
    @Test fun simultaneousInstallKeepsTheHigherSignedCatalog() = concurrentInstall(null)

    private fun boundaryAsset(name: String): ByteArray {
        org.junit.Assume.assumeTrue("Requires opt-in signed assets and immutable disposable QA pins",
            androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("rotationRoute") == "size-boundary")
        return ctx.assets.open("rotation-boundary-$name.json").use { it.readBytes() }
    }

    @Test fun exactMaximumUtf8EnvelopeIsAcceptedAndPersisted() {
        val bytes = boundaryAsset("exact")
        assertEquals(ThreatCatalog.MAX_BYTES, bytes.size)
        val raw = bytes.toString(Charsets.UTF_8)
        assertTrue("Must count UTF-8 bytes, not characters", raw.length < bytes.size)
        assertArrayEquals(bytes, ThreatCatalog.readBounded(bytes.inputStream()))
        val parsed = ThreatCatalog.parse(raw)
        assertEquals(12, parsed.version)
        assertEquals("Proteção sintética UTF-8: ação, café, 漢字, 🛡️",
            parsed.indicators.getValue("com.catalogvalidation.fixture").entry.description)
        assertTrue(ThreatCatalog.install(ctx, raw))
        assertEquals(parsed, ThreatCatalog.snapshot())
        assertArrayEquals(bytes, cache.readBytes())
        assertFalse(File(cache.path + ".new").exists())
        assertFalse(File(cache.path + ".bak").exists())
        boundaryStatus("EXACT_MAX_ACCEPTED bytes=${bytes.size} version=${parsed.version}")
    }

    @Test fun maximumPlusOneValidUtf8EnvelopeIsRejectedWithinBoundAndPreservesCache() {
        val exact = boundaryAsset("exact")
        assertTrue(ThreatCatalog.install(ctx, exact.toString(Charsets.UTF_8)))
        val before = ThreatCatalog.snapshot()
        val bytes = boundaryAsset("plus-one")
        assertEquals(ThreatCatalog.MAX_BYTES + 1, bytes.size)
        val raw = bytes.toString(Charsets.UTF_8)
        assertTrue(raw.length < ThreatCatalog.MAX_BYTES)
        // Prove this is not a malformed/checksum/signature/key-policy rejection.
        val envelope = JSONObject(raw)
        val payload = envelope.getString("payload")
        assertEquals(CatalogTestEnvironment.sha(payload.toByteArray(Charsets.UTF_8)),
            envelope.getString("sha256"))
        val keyId = envelope.getString("keyId")
        val key = ThreatCatalogKeys.trusted.getValue(keyId)
        assertFalse(key.revoked)
        val version = JSONObject(payload).getInt("version")
        assertEquals(13, version)
        assertTrue(version in key.minimumVersion..key.maximumVersion)
        val der = java.util.Base64.getDecoder().decode(key.spkiBase64)
        assertEquals(keyId, CatalogTestEnvironment.sha(der))
        val publicKey = java.security.KeyFactory.getInstance("RSA")
            .generatePublic(java.security.spec.X509EncodedKeySpec(der))
        val verifier = java.security.Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update("maguardian/threat-catalog/v2\n$keyId\n${envelope.getString("sha256")}\n$payload"
            .toByteArray(Charsets.UTF_8))
        assertTrue(verifier.verify(java.util.Base64.getDecoder().decode(envelope.getString("signature"))))
        val exactPayload = JSONObject(JSONObject(exact.toString(Charsets.UTF_8)).getString("payload"))
        // Same validated schema and indicators as the accepted catalog, only newer.
        assertEquals(exactPayload.toString(), JSONObject(payload).put("version", 12).toString())
        val stream = object : java.io.ByteArrayInputStream(bytes) {
            var consumed = 0
            var calls = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                calls++
                return super.read(buffer, offset, length).also { if (it > 0) consumed += it }
            }
        }
        try {
            ThreatCatalog.readBounded(stream)
            fail("MAX_BYTES + 1 stream accepted")
        } catch (failure: IllegalArgumentException) {
            assertEquals("Catálogo muito grande", failure.message)
        }
        assertEquals(ThreatCatalog.MAX_BYTES + 1, stream.consumed)
        assertEquals(ThreatCatalog.MAX_BYTES / 8192 + 1, stream.calls)
        try {
            ThreatCatalog.parse(raw)
            fail("MAX_BYTES + 1 envelope parsed")
        } catch (_: IllegalArgumentException) {}
        rejected(raw)
        assertEquals(before, ThreatCatalog.snapshot())
        assertArrayEquals(exact, cache.readBytes())
        assertFalse(File(cache.path + ".new").exists())
        assertFalse(File(cache.path + ".bak").exists())
        boundaryStatus("MAX_PLUS_ONE_REJECTED bytes=${bytes.size} consumed=${stream.consumed} reads=${stream.calls} cacheUnchanged=true signatureValid=true")
    }

    private fun boundaryStatus(message: String) {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(
            0, android.os.Bundle().apply { putString("stream", "$message\n") })
    }

    private fun concurrentInstall(first: Int?) {
        val rotation = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("rotationRoute") == "concurrent"
        fun asset(name: String) = ctx.assets.open("rotation-$name.json").bufferedReader().use { it.readText() }
        val fixture = if (rotation) "com.catalogvalidation.fixture" else pkg
        val hash = CatalogTestEnvironment.sha(File(ctx.packageManager.getApplicationInfo(fixture, 0).sourceDir).readBytes())
        val indicator = CatalogTestEnvironment.indicator(fixture, hash)
        val lower = if (rotation) asset("concurrent-lower") else CatalogTestEnvironment.catalog(22, indicator)
        val higher = if (rotation) asset("direct-old-cache") else CatalogTestEnvironment.catalog(23, indicator)
        val lowVersion = ThreatCatalog.parse(lower).version
        val highVersion = ThreatCatalog.parse(higher).version
        val firstVersion = when (first) { 22 -> lowVersion; 23 -> highVersion; else -> null }
        assertTrue(ThreatCatalog.install(ctx, if (rotation) asset("old-cache") else CatalogTestEnvironment.catalog(21, indicator)))
        PrefsHelper.markThreatKept(ctx, fixture)
        // Drain apply() before taking evidence; no preference write during the race.
        assertTrue(ctx.getSharedPreferences("maguardian_prefs", 0).edit().commit())
        val kept = PrefsHelper.getThreats(ctx).toString()
        val results = CatalogConcurrentInstall.race(ctx, lower, higher, firstVersion)
        CatalogConcurrentInstall.assertResults(results, lowVersion, highVersion, firstVersion)
        assertEquals(ThreatCatalog.parse(higher), ThreatCatalog.snapshot())
        assertArrayEquals(higher.toByteArray(Charsets.UTF_8), cache.readBytes())
        assertEquals(CatalogTestEnvironment.sha(higher.toByteArray(Charsets.UTF_8)),
            CatalogTestEnvironment.sha(cache.readBytes()))
        assertFalse(File(cache.path + ".new").exists())
        assertFalse(File(cache.path + ".bak").exists())
        assertNotNull(ThreatCatalog.match(ctx, fixture))
        assertTrue(PrefsHelper.isThreatKept(ctx, fixture))
        assertNull(MalwareDatabase.isMalware(fixture, ctx))
        assertEquals(kept, PrefsHelper.getThreats(ctx).toString())
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(
            0, android.os.Bundle().apply {
                putString("stream", "CONCURRENT_ORDER_OK first=$firstVersion results=$results version=$highVersion diskSha256=${CatalogTestEnvironment.sha(cache.readBytes())} snapshotHash=${ThreatCatalog.snapshot()!!.hash} detection=true manter=true\n")
            })
    }

    private fun rejected(raw: String) {
        try {
            ThreatCatalog.install(ctx, raw)
            fail("Invalid update accepted")
        } catch (_: IllegalArgumentException) {
        } catch (_: org.json.JSONException) {
        }
    }

    @Test fun invalidSignaturesAndLegacyUpdatesPreserveCacheAndKeepChoice() {
        val signed = CatalogTestEnvironment.catalog(2)
        PrefsHelper.markThreatKept(ctx, pkg)
        ThreatCatalog.install(ctx, signed)
        val original = ThreatCatalog.snapshot()
        val before = cache.readText()
        rejected("{}")
        rejected(JSONObject(signed).put("signature", "!".repeat(512)).toString())
        rejected(JSONObject(signed).put("signature",
            java.util.Base64.getEncoder().encodeToString(ByteArray(384))).toString())
        rejected(JSONObject(signed).put("keyId", "0".repeat(64)).toString())
        rejected(JSONObject(signed).put("format", 1).apply {
            remove("signature"); remove("keyId")
        }.toString())
        assertEquals(original, ThreatCatalog.snapshot())
        assertEquals(before, cache.readText())
        assertFalse(ThreatCatalog.install(ctx, signed))
        assertTrue(PrefsHelper.isThreatKept(ctx, pkg))
        assertNull(MalwareDatabase.isMalware(pkg, ctx))
        assertNull(MalwareDatabase.isMalware("com.google.example", ctx))
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
        assertTrue(MalwareDatabase.isTrustedApp(bank))
        assertTrue(MalwareDatabase.isScanExempt(exempt))
        assertFalse(MalwareDatabase.getAllPackageNames().contains(trusted))
        assertFalse(MalwareDatabase.getAllPackageNames().contains(bank))
        PrefsHelper.markThreatKept(ctx, bundled)
        ThreatCatalog.install(ctx, CatalogTestEnvironment.catalog(2))
        CatalogTestEnvironment.resetMemory()
        ThreatCatalog.initialize(ctx)
        assertTrue(PrefsHelper.isThreatKept(ctx, bundled))
        assertNull(MalwareDatabase.isMalware(bundled, ctx))
    }

    @Test fun installationHandlerAndStartupScanUseLocalHashAndRespectKeep() {
        // The instrumentation APK itself is a harmless installed fixture; never fetch an APK.
        val fixture = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().context.packageName
        assertEquals("com.catalogvalidation.fixture", fixture)
        assertFalse(MalwareDatabase.isSystemPrefix(fixture))
        assertFalse(MalwareDatabase.isTrustedApp(fixture))
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
        // /dev/full throws ENOSPC. On SELinux-restricted emulators, /dev/null
        // accepts the bytes but fd.sync throws, exercising the same rollback path.
        val full = File("/dev/full")
        val sink = if (full.exists() && full.canWrite()) full else File("/dev/null")
        assertTrue("Need a writable failure-injection device", sink.exists() && sink.canWrite())
        android.system.Os.symlink(sink.path, cache.path + ".new")
        try {
            try {
                ThreatCatalog.install(ctx, CatalogTestEnvironment.catalog(2))
                fail("Write should fail")
            } catch (failure: java.io.IOException) {
                if (sink.path == "/dev/null") {
                    assertTrue("Expected a real sync failure, not a skipped write",
                        failure is java.io.SyncFailedException)
                }
            }
            assertEquals(1, ThreatCatalog.snapshot()!!.version)
            assertEquals(before, cache.readText())
        } finally { File(cache.path + ".new").delete() }
    }
}
