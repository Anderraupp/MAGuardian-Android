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
 * Toda vez que qualquer app é instalado ou atualizado no dispositivo,
 * o Android chama onReceive() automaticamente — sem precisar abrir o app malicioso.
 * É uma exceção garantida pelo Android mesmo em segundo plano (API 26+).
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

        Log.i(TAG, "App detectado: $packageName | substituindo=$isReplacing")

        // ── 1. Verificação no banco de malware conhecido ──
        val malware = MalwareDatabase.isMalware(packageName)
        if (malware != null) {
            Log.w(TAG, "MALWARE instalado: $packageName")
            PrefsHelper.saveThreat(context, malware)
            sendThreatNotification(context, malware)
            return
        }

        // ── 2. Verificação heurística (nome + permissões) ──
        val appLabel = try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) { packageName }

        val pkgInfo = try {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        } catch (e: Exception) { null }

        val heuristic = MalwareDatabase.checkHeuristic(
            packageName, appLabel, pkgInfo?.requestedPermissions
        )
        if (heuristic != null) {
            Log.w(TAG, "Suspeito heurístico instalado: $packageName")
            PrefsHelper.saveThreat(context, heuristic)
            sendThreatNotification(context, heuristic)
        }
    }

    private fun sendThreatNotification(context: Context, malware: MalwareDatabase.MalwareEntry) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Garante que o canal de ameaças existe mesmo se o serviço não estiver rodando
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
