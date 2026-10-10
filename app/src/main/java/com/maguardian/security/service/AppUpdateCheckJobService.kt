package com.maguardian.security.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.maguardian.security.util.AppUpdateNotifier
import java.util.concurrent.TimeUnit

/** Consulta leve, agendada pelo Android: não mantém um serviço permanente. */
class AppUpdateCheckJobService : JobService() {
    companion object {
        private const val JOB_ID = 4001
        private const val TAG = "AppUpdateCheckJob"

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, AppUpdateCheckJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(TimeUnit.HOURS.toMillis(12))
                .setPersisted(true)
                .build()
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                Log.w(TAG, "Não foi possível agendar a consulta de atualização")
            }
        }
    }

    private var activeJob: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        activeJob = params
        try {
            AppUpdateManagerFactory.create(applicationContext).appUpdateInfo
                .addOnCompleteListener { task ->
                    if (activeJob !== params) return@addOnCompleteListener
                    try {
                        if (task.isSuccessful) {
                            AppUpdateNotifier.handle(applicationContext, task.result)
                        } else {
                            // APK instalado fora do Play ou indisponibilidade temporária.
                            // Nunca emitir aviso com base em falha ou versão inventada.
                            Log.w(TAG, "Google Play não informou atualização", task.exception)
                        }
                    } catch (e: RuntimeException) {
                        Log.w(TAG, "Falha ao preparar aviso de atualização", e)
                    } finally {
                        activeJob = null
                        jobFinished(params, false)
                    }
                }
            return true
        } catch (e: RuntimeException) {
            activeJob = null
            Log.w(TAG, "Não foi possível consultar o Google Play", e)
            return false
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        activeJob = null
        return true
    }
}
