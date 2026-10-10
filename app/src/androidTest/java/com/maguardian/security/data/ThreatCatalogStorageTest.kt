package com.maguardian.security.data

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.security.MessageDigest
import com.maguardian.security.util.PrefsHelper

/** Device tests: actual Android AtomicFile, PackageManager and preference state. */
class ThreatCatalogStorageTest {
    private fun empty(version: Int): String {
        val payload = """{"schemaVersion":1,"version":$version,"publishedAt":"2026-10-10T00:00:00Z","indicators":[]}"""
        val hash = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return JSONObject().put("format", 1).put("payload", payload).put("sha256", hash).toString()
    }
    @Test fun invalidAndOldUpdatesDoNotChangeCacheOrKeepChoice() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ThreatCatalog.initialize(ctx)
        val original = ThreatCatalog.snapshot()?.version ?: 0
        val v = original + 1
        val packageName = "com.example.catalogkept"
        PrefsHelper.markThreatKept(ctx, packageName)
        assertTrue(ThreatCatalog.install(ctx, empty(v)))
        val file = java.io.File(ctx.noBackupFilesDir, "threat-catalog.json")
        val before = file.readText()
        try { ThreatCatalog.install(ctx, "{}"); fail("Invalid update accepted") } catch (_: Exception) {}
        assertEquals(v, ThreatCatalog.snapshot()!!.version)
        assertEquals(before, file.readText())
        if (v > 1) {
            try { ThreatCatalog.install(ctx, empty(v - 1)); fail("Rollback accepted") } catch (_: Exception) {}
            assertEquals(before, file.readText())
        }
        assertFalse(ThreatCatalog.install(ctx, empty(v)))
        assertTrue(PrefsHelper.isThreatKept(ctx, packageName))
        assertNull(MalwareDatabase.isMalware(packageName, ctx))
        assertNull(MalwareDatabase.isMalware("com.google.example", ctx))
        assertTrue(MalwareDatabase.KNOWN_MALWARE.isNotEmpty())
    }
}
