package com.maguardian.security.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Only metadata. Independent RSA-3072 signatures are verified against public
 * keys embedded in the APK, in addition to HTTPS and the integrity checksum.
 * Downloaded data cannot add keys or change the trust policy.
 */
object ThreatCatalog {
    const val MAX_BYTES = 2 * 1024 * 1024
    data class Indicator(val entry: MalwareDatabase.MalwareEntry, val hashes: Set<String>)
    data class Snapshot(val version: Int, val hash: String, val publishedAt: String,
                        val indicators: Map<String, Indicator>, val notices: Set<String>)
    @Volatile private var current: Snapshot? = null
    private var initialized = false
    private data class ApkHash(val identity: String, val hash: String)
    private val apkHashes = java.util.concurrent.ConcurrentHashMap<String, ApkHash>()

    private fun file(ctx: Context) = AtomicFile(File(ctx.noBackupFilesDir, "threat-catalog.json"))
    fun snapshot(): Snapshot? = current

    @Synchronized
    fun initialize(ctx: Context) {
        if (initialized) return
        try {
            // Revalidate against this APK's immutable policy. A still-trusted
            // newer cache survives recovery; bootstrap is only a fallback,
            // never a reason to downgrade valid persisted metadata.
            val raw = file(ctx).openRead().use { readBounded(it) }.toString(Charsets.UTF_8)
            current = parse(raw)
        } catch (_: Exception) {
            // Legacy unsigned/corrupt caches never become a trust anchor.
            // The signed APK bootstrap retains the reviewed baseline offline.
            try {
                current = ctx.assets.open("threat-catalog-bootstrap.json")
                    .use { parse(readBounded(it).toString(Charsets.UTF_8)) }
            } catch (_: Exception) {
                // The original embedded database remains usable regardless.
            }
        }
        initialized = true
    }

    @Synchronized
    fun install(ctx: Context, raw: String): Boolean {
        initialize(ctx)
        val next = parse(raw)
        val old = current
        require(old == null || next.version >= old.version) { "Versão antiga rejeitada" }
        require(old == null || next.version != old.version || next.hash == old.hash) {
            "Versão reutilizada com conteúdo diferente"
        }
        if (next.version == old?.version) return false
        val atomic = file(ctx)
        val stream = atomic.startWrite()
        try {
            stream.write(raw.toByteArray(Charsets.UTF_8))
            // AtomicFile logs some sync/rename failures instead of throwing.
            // Make sync failure explicit and confirm replacement before publishing.
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
        // finishWrite has already closed the stream; do not call failWrite on it.
        check(atomic.openRead().use { readBounded(it) }.toString(Charsets.UTF_8) == raw) {
            "Não foi possível confirmar a gravação do catálogo"
        }
        // Publish the immutable snapshot only AFTER durable atomic replacement.
        current = next
        apkHashes.clear()
        return true
    }

    fun match(ctx: Context, packageName: String): MalwareDatabase.MalwareEntry? {
        initialize(ctx)
        val indicator = current?.indicators?.get(packageName) ?: return null
        return try {
            // Read only a matching local APK. Nothing is downloaded or uploaded.
            val info = ctx.packageManager.getApplicationInfo(packageName, 0)
            val apk = File(info.sourceDir)
            val packageInfo = ctx.packageManager.getPackageInfo(packageName, 0)
            val identity = "${info.sourceDir}:${apk.length()}:${apk.lastModified()}:${packageInfo.lastUpdateTime}"
            val hash = apkHashes[packageName]?.takeIf { it.identity == identity }?.hash ?: apk.inputStream().use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(32768)
                var n = stream.read(buffer)
                while (n != -1) {
                    digest.update(buffer, 0, n)
                    n = stream.read(buffer)
                }
                hex(digest.digest())
            }.also { apkHashes[packageName] = ApkHash(identity, it) }
            indicator.entry.takeIf { hash in indicator.hashes }
        } catch (_: Exception) { null }
    }

    fun readBounded(stream: java.io.InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var n = stream.read(buffer)
        while (n != -1) {
            require(output.size() + n <= MAX_BYTES) { "Catálogo muito grande" }
            output.write(buffer, 0, n)
            n = stream.read(buffer)
        }
        return output.toByteArray()
    }

    fun parse(raw: String, trusted: Map<String, ThreatCatalogKeys.Key> = ThreatCatalogKeys.trusted): Snapshot {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val root = JSONObject(raw)
        keys(root, setOf("format", "payload", "sha256", "keyId", "signature"))
        require(root.get("format") is Number && root.getDouble("format") == 2.0)
        val payload = string(root, "payload", MAX_BYTES)
        val expected = string(root, "sha256", 64)
        require(expected.matches(Regex("[0-9a-f]{64}")))
        require(hex(MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))) == expected)
        val keyId = string(root, "keyId", 64)
        require(keyId.matches(Regex("[0-9a-f]{64}")))
        val key = requireNotNull(trusted[keyId]) { "Chave de assinatura desconhecida" }
        require(!key.revoked) { "Chave de assinatura revogada" }
        val der = base64(key.spkiBase64)
        require(hex(MessageDigest.getInstance("SHA-256").digest(der)) == keyId)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        require(publicKey is RSAPublicKey && publicKey.modulus.bitLength() == 3072)
        val signed = base64(string(root, "signature", 512))
        require(signed.size == 384)
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update("maguardian/threat-catalog/v2\n$keyId\n$expected\n$payload".toByteArray(Charsets.UTF_8))
        require(verifier.verify(signed)) { "Assinatura do catálogo inválida" }
        val data = JSONObject(payload)
        keys(data, setOf("schemaVersion", "version", "publishedAt", "indicators"))
        require(data.get("schemaVersion") is Number && data.getDouble("schemaVersion") == 1.0)
        val versionValue = data.get("version")
        require(versionValue is Number)
        val version = data.getLong("version")
        require(version in 1..Int.MAX_VALUE.toLong() && data.getDouble("version") == version.toDouble())
        require(version in key.minimumVersion.toLong()..key.maximumVersion.toLong()) {
            "Versão fora da política da chave"
        }
        val publishedAt = string(data, "publishedAt", 40)
        val published = instant(publishedAt)
        require(published <= System.currentTimeMillis() + 86400000)
        val entries = data.getJSONArray("indicators")
        require(entries.length() <= 10000)
        val result = mutableMapOf<String, Indicator>()
        val notices = mutableSetOf<String>()
        for (i in 0 until entries.length()) {
            val item = entries.getJSONObject(i)
            keys(item, setOf("packageName", "appName", "threatType", "severity", "description",
                "apkSha256", "status", "source", "review"))
            val pkg = string(item, "packageName", 255)
            require(pkg.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")))
            require(!result.containsKey(pkg))
            require(string(item, "status", 20) == "confirmed")
            val type = string(item, "threatType", 30)
            require(type in setOf("popup", "overlay", "notification"))
            val severity = string(item, "severity", 10)
            require(severity in setOf("low", "medium", "high"))
            val hashes = item.getJSONArray("apkSha256")
            require(hashes.length() in 1..100)
            val hashSet = (0 until hashes.length()).map {
                val hash = hashes.get(it)
                require(hash is String && hash.matches(Regex("[0-9a-f]{64}")))
                hash as String
            }.toSet()
            val source = item.getJSONObject("source")
            keys(source, setOf("name", "url", "license", "licenseUrl", "attribution"))
            val sourceName = string(source, "name", 120)
            val sourceUrl = string(source, "url", 1000)
            url(sourceUrl)
            val license = string(source, "license", 120)
            val licenseUrl = string(source, "licenseUrl", 1000)
            url(licenseUrl)
            val attribution = string(source, "attribution", 4000)
            notices.add("$sourceName\n$sourceUrl\nLicença: $license\n$licenseUrl\n\n$attribution")
            val review = item.getJSONObject("review")
            keys(review, setOf("reviewer", "reviewedAt", "evidence"))
            string(review, "reviewer", 120)
            require(instant(string(review, "reviewedAt", 40)) <= published)
            string(review, "evidence", 2000)
            result[pkg] = Indicator(MalwareDatabase.MalwareEntry(pkg,
                string(item, "appName", 120), type, severity, string(item, "description", 1000)), hashSet)
        }
        return Snapshot(version.toInt(), expected, publishedAt, result.toMap(), notices.toSet())
    }

    private fun keys(obj: JSONObject, allowed: Set<String>) {
        require(obj.keys().asSequence().toSet() == allowed) { "Campos inválidos" }
    }
    private fun string(obj: JSONObject, name: String, max: Int): String {
        val value = obj.get(name)
        require(value is String && value.isNotBlank() && value.length <= max)
        return value
    }
    private fun url(value: String) {
        val uri = java.net.URI(value)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null)
    }
    private fun instant(value: String): Long {
        require(value.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{3})?Z")))
        val fmt = SimpleDateFormat(if (value.contains('.')) "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
            else "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        fmt.isLenient = false
        return requireNotNull(fmt.parse(value)).time
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun base64(value: String): ByteArray {
        val decoded = Base64.getDecoder().decode(value)
        require(Base64.getEncoder().encodeToString(decoded) == value) { "Base64 inválido" }
        return decoded
    }
}
