package com.maguardian.security.data

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.maguardian.security.util.PrefsHelper
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

object ThreatCatalogUpdater {
    const val URL_CATALOG = "https://raw.githubusercontent.com/Anderraupp/MAGuardian-Android/main/threat-catalog/catalog.json"
    const val UPDATED = "com.maguardian.security.CATALOG_UPDATED"
    private const val JOB_ID = 74021
    private val running = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun schedule(ctx: Context) {
        val scheduler = ctx.getSystemService(JobScheduler::class.java) ?: run {
            PrefsHelper.setCatalogStatus(ctx, "Agendador de atualizações indisponível.")
            return
        }
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val result = scheduler.schedule(JobInfo.Builder(JOB_ID,
            ComponentName(ctx, ThreatCatalogJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(24 * 60 * 60 * 1000L)
            .build())
        if (result != JobScheduler.RESULT_SUCCESS) {
            PrefsHelper.setCatalogStatus(ctx, "Não foi possível agendar atualização automática.")
        }
    }

    fun update(ctx: Context, manual: Boolean = false, done: ((String) -> Unit)? = null) {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        val elapsed = now - PrefsHelper.getCatalogAttempt(app)
        if (!manual && elapsed in 0 until 6 * 60 * 60 * 1000L) {
            main.post { done?.invoke(PrefsHelper.getCatalogStatus(app)) }
            return
        }
        if (!running.compareAndSet(false, true)) {
            main.post { done?.invoke("Uma atualização já está em andamento.") }
            return
        }
        PrefsHelper.setCatalogAttempt(app, now)
        worker.execute {
            var connection: HttpsURLConnection? = null
            val status = try {
                connection = URL(URL_CATALOG).openConnection() as HttpsURLConnection
                connection!!.apply {
                    // Default Android TLS certificate + hostname verification; never bypass.
                    instanceFollowRedirects = false
                    connectTimeout = 15000
                    readTimeout = 15000
                    requestMethod = "GET"
                    useCaches = false
                    setRequestProperty("Accept", "application/json")
                }
                require(connection!!.responseCode == 200) { "Resposta HTTP inválida" }
                require(connection!!.contentLengthLong <= ThreatCatalog.MAX_BYTES)
                val raw = connection!!.inputStream.use { ThreatCatalog.readBounded(it) }.toString(Charsets.UTF_8)
                val changed = ThreatCatalog.install(app, raw)
                PrefsHelper.setCatalogSuccess(app, System.currentTimeMillis())
                if (changed) {
                    app.sendBroadcast(Intent(UPDATED).setPackage(app.packageName))
                    "Base atualizada. As próximas verificações usarão os novos indicadores."
                } else "A base já está atualizada."
            } catch (_: Exception) {
                "Falha na atualização. A última base válida e a base embarcada foram preservadas."
            } finally {
                connection?.disconnect()
            }
            PrefsHelper.setCatalogStatus(app, status)
            running.set(false)
            main.post { done?.invoke(status) }
        }
    }
}

class ThreatCatalogJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        ThreatCatalogUpdater.update(this, done = { jobFinished(params, false) })
        return true
    }
    // Any already-started bounded download may finish safely; there is no UI or
    // inventory work. The next periodic job will check again.
    override fun onStopJob(params: JobParameters): Boolean = false
}
