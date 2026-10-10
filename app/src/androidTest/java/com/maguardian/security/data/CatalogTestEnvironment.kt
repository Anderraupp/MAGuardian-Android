package com.maguardian.security.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.runner.AndroidJUnitRunner
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** No billing, analytics, live downloads or automatic jobs during instrumentation. */
class CatalogTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        check(context.packageName == "com.maguardian.security.catalogvalidation") {
            "Catalog tests are forbidden on the normal application"
        }
        return super.newApplication(cl, Application::class.java.name, context)
    }
}

internal object CatalogTestEnvironment {
    fun target(): Context {
        check(InstrumentationRegistry.getArguments().getString("disposable") == "true") {
            "Run only on a disposable emulator with -e disposable true"
        }
        return InstrumentationRegistry.getInstrumentation().targetContext.also {
            check(it.packageName == "com.maguardian.security.catalogvalidation")
            installFixtureKey(it)
        }
    }

    private var fixtureKey: java.security.KeyPair? = null

    // Generated only on the disposable device; no production private key is
    // needed or shipped. Persist so signatures remain verifiable across reboot.
    private fun installFixtureKey(ctx: Context) {
        if (fixtureKey == null) {
            val file = File(ctx.noBackupFilesDir, "catalog-validation-fixture-key.json")
            val factory = java.security.KeyFactory.getInstance("RSA")
            fixtureKey = if (file.exists()) {
                val saved = JSONObject(file.readText())
                java.security.KeyPair(
                    factory.generatePublic(java.security.spec.X509EncodedKeySpec(
                        java.util.Base64.getDecoder().decode(saved.getString("public")))),
                    factory.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(
                        java.util.Base64.getDecoder().decode(saved.getString("private"))))
                )
            } else {
                java.security.KeyPairGenerator.getInstance("RSA").apply {
                    initialize(3072)
                }.generateKeyPair().also { pair ->
                    file.writeText(JSONObject()
                        .put("public", java.util.Base64.getEncoder().encodeToString(pair.public.encoded))
                        .put("private", java.util.Base64.getEncoder().encodeToString(pair.private.encoded))
                        .toString())
                }
            }
        }
        // The build-only QA provider is mutable; production policies are never
        // replaced by reflection and never accept this generated fixture key.
        val keys = ThreatCatalogKeys.trusted as MutableMap<String, ThreatCatalogKeys.Key>
        val encoded = fixtureKey!!.public.encoded
        keys[sha(encoded)] = ThreatCatalogKeys.Key(
            java.util.Base64.getEncoder().encodeToString(encoded), 1, Int.MAX_VALUE, false)
    }

    // Test-only reflection: no reset/backdoor is included in the shipped APK.
    fun resetMemory() {
        for ((name, value) in listOf("current" to null, "initialized" to false)) {
            ThreatCatalog::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(ThreatCatalog, value)
            }
        }
        ThreatCatalog::class.java.getDeclaredField("apkHashes").apply {
            isAccessible = true
            (get(ThreatCatalog) as MutableMap<*, *>).clear()
        }
    }

    class Sandbox(base: Context) : ContextWrapper(base) {
        private val id = "catalog-test-${java.util.UUID.randomUUID()}"
        val directory = File(base.cacheDir, id).apply { check(mkdirs()) }
        private val preferences = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "$id-$name"
            preferences.add(isolatedName)
            return baseContext.getSharedPreferences(isolatedName, mode)
        }
        fun close() {
            preferences.forEach { baseContext.deleteSharedPreferences(it) }
            directory.deleteRecursively()
        }
    }

    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun indicator(pkg: String, hash: String, severity: String = "high"): JSONObject =
        JSONObject().put("packageName", pkg).put("appName", "Harmless test fixture")
            .put("threatType", "popup").put("severity", severity)
            .put("description", "Instrumentation fixture, not a real threat")
            .put("apkSha256", JSONArray().put(hash)).put("status", "confirmed")
            .put("source", JSONObject().put("name", "Test fixture")
                .put("url", "https://example.org/fixture").put("license", "CC0-1.0")
                .put("licenseUrl", "https://creativecommons.org/publicdomain/zero/1.0/")
                .put("attribution", "Synthetic metadata for isolated tests"))
            .put("review", JSONObject().put("reviewer", "Instrumentation")
                .put("reviewedAt", "2026-01-01T00:00:00Z").put("evidence", "Local fixture"))

    fun catalog(version: Int, vararg indicators: JSONObject): String {
        val payload = JSONObject().put("schemaVersion", 1).put("version", version)
            .put("publishedAt", "2026-01-01T00:00:00Z")
            .put("indicators", JSONArray(indicators.toList())).toString()
        checkNotNull(fixtureKey) { "Call target() before creating signed fixtures." }
        val hash = sha(payload.toByteArray(Charsets.UTF_8))
        val keyId = sha(fixtureKey!!.public.encoded)
        val signer = java.security.Signature.getInstance("SHA256withRSA")
        signer.initSign(fixtureKey!!.private)
        signer.update("maguardian/threat-catalog/v2\n$keyId\n$hash\n$payload".toByteArray(Charsets.UTF_8))
        return JSONObject().put("format", 2).put("payload", payload)
            .put("sha256", hash).put("keyId", keyId)
            .put("signature", java.util.Base64.getEncoder().encodeToString(signer.sign())).toString()
    }
}
