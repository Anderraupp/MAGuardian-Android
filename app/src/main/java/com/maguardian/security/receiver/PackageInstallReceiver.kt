package com.maguardian.security.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.maguardian.security.R
import com.maguardian.security.data.MalwareDatabase
import com.maguardian.security.service.PopupDetectorService
import com.maguardian.security.ui.MainActivity
import com.maguardian.security.util.PrefsHelper

/**
 * Receptor que escuta PACKAGE_ADDED e PACKAGE_REPLACED.
 * Também registrado dinamicamente: broadcasts implícitos do manifesto não
 * garantem entrega em segundo plano nas versões recentes do Android.
 */
class PackageInstallReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "PackageInstallReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_PACKAGE_ADDED && action != Intent.ACTION_PACKAGE_REPLACED) return

        val packageName = intent.data?.schemeSpecificPart ?: return
        val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)

        // Ignorar atualização do próprio app
        if (packageName == context.packageName) return
        if (PrefsHelper.isThreatKept(context, packageName)) return
        val pending = goAsync()
        Thread {
            try {
                checkInstalled(context.applicationContext, packageName, isReplacing)
            } catch (e: Exception) {
                Log.e(TAG, "Não foi possível verificar a instalação", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    @Synchronized
    internal fun checkInstalled(context: Context, packageName: String, isReplacing: Boolean) {
        if (packageName == context.packageName ||
            PrefsHelper.isThreatKept(context, packageName) ||
            MalwareDatabase.isSystemPrefix(packageName) ||
            MalwareDatabase.isTrustedApp(packageName) ||
            MalwareDatabase.isScanExempt(packageName)) return
        val pkgInfo = try {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        } catch (_: PackageManager.NameNotFoundException) { return }
        Log.i(TAG, "App detectado: $packageName | substituindo=$isReplacing")

        // ── 1. Verificação no banco de malware conhecido ──
        val malware = MalwareDatabase.isMalware(packageName, context)
        if (malware != null) {
            Log.w(TAG, "MALWARE instalado: $packageName")
            recordFinding(context, malware)
            return
        }

        // ── 2. Verificação heurística (nome + permissões) ──
        val appLabel = try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) { packageName }

        val appInfo = pkgInfo.applicationInfo ?: return
        val hasLauncher = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).any { it.activityInfo?.packageName == packageName }
        val system = appInfo.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
            android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        val heuristic = MalwareDatabase.checkHiddenApp(
            packageName, appLabel, pkgInfo.requestedPermissions, hasLauncher, system
        ) ?: MalwareDatabase.checkHeuristic(packageName, appLabel, pkgInfo.requestedPermissions)
        if (heuristic != null) {
            Log.w(TAG, "Suspeito heurístico instalado: $packageName")
            recordFinding(context, heuristic)
        }
    }

    private fun recordFinding(context: Context, finding: MalwareDatabase.MalwareEntry): Unit = synchronized(InstallThreatMonitor) {
        // Manter can be chosen while an APK hash is being checked.
        if (PrefsHelper.isThreatKept(context, finding.packageName)) return
        val records = PrefsHelper.getThreats(context)
        val alreadyActive = (0 until records.length()).any {
            val record = records.getJSONObject(it)
            record.optString("packageName") == finding.packageName &&
                record.optString("status", "detected") == "detected"
        }
        PrefsHelper.saveThreat(context, finding)
        context.sendBroadcast(Intent("com.maguardian.security.THREAT_DETECTED").setPackage(context.packageName))
        if (!alreadyActive) sendThreatNotification(context, finding)
    }

    private fun sendThreatNotification(context: Context, malware: MalwareDatabase.MalwareEntry) {
        if (!PrefsHelper.isNotificationsEnabled(context)) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.areNotificationsEnabled()) return
        if (!PrefsHelper.hasFullAccess(context)) {
            val subscription = PendingIntent.getActivity(context, 2,
                Intent(context, com.maguardian.security.ui.SubscriptionActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, PopupDetectorService.CHANNEL_ID_THREAT)
                .setSmallIcon(R.drawable.ic_shield_alert)
                .setContentTitle("Ameaça detectada no dispositivo")
                .setContentText("Uma ameaça foi identificada. Seja Premium para ver os detalhes e as opções.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(subscription)
                .addAction(R.drawable.ic_shield_alert, "Seja Premium", subscription)
                .setColor(0xFFDC2626.toInt())
            ensureChannel(nm)
            nm.notify(malware.packageName.hashCode(), notification.build())
            return
        }

        // Garante que o canal de ameaças existe mesmo se o serviço não estiver rodando
        ensureChannel(nm)
        sendFullNotification(context, malware, nm)
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(PopupDetectorService.CHANNEL_ID_THREAT) == null) {
                NotificationChannel(
                    PopupDetectorService.CHANNEL_ID_THREAT,
                    "Ameaças Detectadas",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Alertas de apps maliciosos instalados"
                    enableVibration(true)
                    nm.createNotificationChannel(this)
                }
            }
        }

    }

    private fun sendFullNotification(context: Context, malware: MalwareDatabase.MalwareEntry, nm: NotificationManager) {
        val severity = when (malware.severity) {
            "high" -> "ALTA"
            "medium" -> "MÉDIA"
            else -> "BAIXA"
        }

        // Botão Desinstalar — abre o diálogo de desinstalação do sistema diretamente
        val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:${malware.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val uninstallPendingIntent = PendingIntent.getActivity(
            context,
            malware.packageName.hashCode(),
            uninstallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Toque na notificação → abre o M&A Guardian
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, PopupDetectorService.CHANNEL_ID_THREAT)
            .setSmallIcon(R.drawable.ic_shield_alert)
            .setContentTitle("🚨 App Malicioso Instalado — Severidade $severity")
            .setContentText("${malware.appName} foi instalado no seu dispositivo")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        "${malware.appName} (${malware.packageName})\n" +
                        "${malware.description}\n\n" +
                        "Toque em Desinstalar para removê-lo agora."
                    ),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .addAction(R.drawable.ic_delete, "Desinstalar", uninstallPendingIntent)
            .setColor(0xFFDC2626.toInt())
            .build()

        nm.notify(malware.packageName.hashCode(), notification)
        Log.i(TAG, "Notificação de ameaça enviada: ${malware.packageName}")
    }
}
