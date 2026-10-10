package com.maguardian.security.util

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import com.maguardian.security.data.ThreatCatalogKeys
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.Executors

/** No passwords, backend session or private signing key are stored in the APK. */
object CatalogAdmin {
    private val worker = Executors.newSingleThreadExecutor()
    private const val PREFS = "catalog_admin_ui"
    private const val SCOPE = "catalog-admin-ui"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun nonce(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString("nonce", null) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it.toInt() and 255) }.also { p.edit().putString("nonce", it).apply() }
    }
    private fun valid(ctx: Context, payload: String, signature: String, keyId: String): Boolean = try {
        require(payload.length <= 1024 && signature.length <= 1024)
        val data = JSONObject(payload)
        val expires = data.getLong("expiresAt")
        require(data.getString("scope") == SCOPE && data.getString("nonce") == nonce(ctx))
        require(expires > System.currentTimeMillis() && expires <= System.currentTimeMillis() + 8 * 86400000L)
        val key = ThreatCatalogKeys.trusted[keyId] ?: error("Unknown key")
        require(!key.revoked)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(Base64.decode(key.spkiBase64, Base64.DEFAULT)))
        Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey)
            update("maguardian/admin-device/v1\n$payload".toByteArray(Charsets.UTF_8))
            verify(Base64.decode(signature, Base64.DEFAULT))
        }
    } catch (_: Exception) { false }

    fun isEnabled(ctx: Context): Boolean {
        val p = prefs(ctx)
        return valid(ctx, p.getString("payload", "") ?: "", p.getString("signature", "") ?: "", p.getString("keyId", "") ?: "")
    }
    fun acceptActivation(activity: Activity, uri: Uri?): Boolean {
        if (uri?.scheme != "maguardian-admin" || uri.host != "activate") return false
        val payload = uri.getQueryParameter("payload") ?: ""
        val signature = uri.getQueryParameter("signature") ?: ""
        val keyId = uri.getQueryParameter("keyId") ?: ""
        val accepted = valid(activity, payload, signature, keyId)
        if (accepted) prefs(activity).edit().putString("payload", payload).putString("signature", signature).putString("keyId", keyId).apply()
        Toast.makeText(activity, if (accepted) "Acesso administrativo ativado neste aparelho por 7 dias."
            else "Autorização inválida ou expirada. Entre novamente.", Toast.LENGTH_LONG).show()
        return true
    }
    private fun open(activity: Activity, fragment: String = "") {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${AppConfig.API_BASE_URL}/admin$fragment")))
        } catch (_: Exception) {
            Toast.makeText(activity, "Não foi possível abrir o navegador.", Toast.LENGTH_LONG).show()
        }
    }
    fun connect(activity: Activity) {
        AlertDialog.Builder(activity).setTitle("Administração da base")
            .setMessage("Área exclusiva do proprietário. Entre na sua conta no navegador e autorize este aparelho. Nenhum aplicativo instalado será enviado nesta etapa.")
            .setPositiveButton("Entrar") { _, _ -> open(activity, "#device=${nonce(activity)}") }
            .setNegativeButton("Cancelar", null).show()
    }
    fun manage(activity: Activity) {
        if (!isEnabled(activity)) { connect(activity); return }
        AlertDialog.Builder(activity).setTitle("Administração da base")
            .setItems(arrayOf("Adicionar app instalado", "Abrir base publicada", "Desativar neste aparelho")) { _, which ->
                if (which == 0) chooseInstalled(activity)
                else if (which == 1) open(activity)
                else {
                    prefs(activity).edit().clear().apply()
                    activity.recreate()
                }
            }.show()
    }
    private fun chooseInstalled(activity: Activity) {
        worker.execute {
            try {
                val pm = activity.packageManager
                val apps = pm.getInstalledApplications(0)
                    .filter { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
                    .map { Pair(it.packageName, it.loadLabel(pm).toString()) }
                    .sortedBy { it.second.lowercase() }
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed && isEnabled(activity)) {
                        AlertDialog.Builder(activity).setTitle("Selecione um app — lista somente local")
                            .setItems(apps.map { "${it.second}\n${it.first}" }.toTypedArray()) { _, index ->
                                prepare(activity, apps[index].first, apps[index].second, "")
                            }.setNegativeButton("Cancelar", null).show()
                    }
                }
            } catch (_: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Não foi possível listar os aplicativos.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    fun prepare(activity: Activity, packageName: String, appName: String, description: String) {
        if (!isEnabled(activity)) { connect(activity); return }
        AlertDialog.Builder(activity).setTitle("Adicionar à base compartilhada?")
            .setMessage("Será preparada uma proposta com nome, pacote, SHA-256 do APK e descrição deste app. O APK e sua lista de aplicativos não serão enviados. Você ainda deverá confirmar a evidência e a publicação no navegador.\n\nUma suspeita da varredura, sozinha, não comprova uma ameaça.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Preparar") { _, _ ->
                Toast.makeText(activity, "Calculando identificação do APK…", Toast.LENGTH_SHORT).show()
                worker.execute {
                    try {
                        val info = activity.packageManager.getApplicationInfo(packageName, 0)
                        val hash = MessageDigest.getInstance("SHA-256")
                        File(info.sourceDir).inputStream().use { stream ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val n = stream.read(buffer)
                                if (n < 0) break
                                hash.update(buffer, 0, n)
                            }
                        }
                        val draft = JSONObject().put("packageName", packageName).put("appName", appName.take(120))
                            .put("description", description.take(1000))
                            .put("apkSha256", hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
                        activity.runOnUiThread {
                            if (!activity.isFinishing && !activity.isDestroyed && isEnabled(activity)) {
                                // Fragment is not sent as URL query/referrer or recorded in server request logs.
                                open(activity, "#draft=${Uri.encode(draft.toString())}")
                            }
                        }
                    } catch (_: Exception) {
                        activity.runOnUiThread {
                            Toast.makeText(activity, "Não foi possível identificar o APK. Nenhuma ameaça foi publicada.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }.show()
    }
}
