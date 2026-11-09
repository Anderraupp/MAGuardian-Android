package com.maguardian.security.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.maguardian.security.receiver.InstallThreatMonitor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Best-effort Android scheduling, not a promise of immediate background delivery. */
class InstallThreatCheckJobService : JobService() {
    companion object {
        const val JOB_ID = 4002
        fun schedule(ctx: Context) {
            val scheduler = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, InstallThreatCheckJobService::class.java))
                .setPeriodic(TimeUnit.MINUTES.toMillis(15))
                .setPersisted(true)
                .build()
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                Log.w("InstallThreatCheck", "Não foi possível agendar verificação de instalações")
            }
        }
    }

    private var cancellation: AtomicBoolean? = null
    override fun onStartJob(params: JobParameters): Boolean {
        val stopped = AtomicBoolean(false)
        cancellation = stopped
        Thread {
            var retry = false
            try {
                InstallThreatMonitor.reconcile(applicationContext) { stopped.get() }
            } catch (e: Exception) {
                retry = true
                Log.w("InstallThreatCheck", "Falha na verificação automática", e)
            } finally {
                if (!stopped.get()) jobFinished(params, retry)
            }
        }.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        cancellation?.set(true)
        return true
    }
}
