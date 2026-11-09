package com.maguardian.security.receiver

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.maguardian.security.service.InstallThreatCheckJobService
import com.maguardian.security.util.PrefsHelper

/** Package metadata stays on-device. This does not enable the paid foreground service. */
object InstallThreatMonitor {
    private var registered = false

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        if (registered) return
        ContextCompat.registerReceiver(app, PackageInstallReceiver(), IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }, ContextCompat.RECEIVER_EXPORTED)
        registered = true
        try {
            InstallThreatCheckJobService.schedule(app)
        } catch (e: RuntimeException) {
            Log.w("InstallThreatMonitor", "Falha ao agendar verificação de apoio", e)
        }
        Thread {
            try {
                reconcile(app)
            } catch (e: Exception) {
                Log.w("InstallThreatMonitor", "Falha ao conferir instalações pendentes", e)
            }
        }.start()
    }

    /** Automatic fallback for free users, including after the process was reclaimed. */
    @Synchronized
    fun reconcile(context: Context, stopped: () -> Boolean = { false }) {
        // Subscribers/trial users retain the existing real-time service and pause controls.
        if (PrefsHelper.hasFullAccess(context)) return
        val prefs = context.getSharedPreferences("guardian_install_checks", Context.MODE_PRIVATE)
        val receiver = PackageInstallReceiver()
        val installed = context.packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        val seen = mutableSetOf<String>()
        for (pkg in installed) {
            if (stopped()) return
            val name = pkg.packageName
            if (name == context.packageName) continue
            seen.add(name)
            @Suppress("DEPRECATION")
            val stamp = "${pkg.firstInstallTime}:${pkg.lastUpdateTime}:${pkg.versionCode}"
            if (prefs.getString(name, null) == stamp) continue
            try {
                receiver.checkInstalled(context, name, false)
                prefs.edit().putString(name, stamp).apply()
            } catch (e: Exception) {
                // No fabricated result or completion marker; retry on the next opportunity.
                Log.w("InstallThreatMonitor", "Falha ao verificar instalação", e)
            }
        }
        val edit = prefs.edit()
        prefs.all.keys.filterNot { it in seen }.forEach { edit.remove(it) }
        edit.apply()
    }
}
