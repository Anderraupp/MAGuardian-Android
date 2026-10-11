package com.maguardian.security.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.maguardian.security.R
import com.maguardian.security.service.PopupDetectorService
import com.maguardian.security.ui.MainActivity
import com.maguardian.security.util.PrefsHelper

/**
 * Checks only APKs named in the validated catalog. No heuristics, inventory upload,
 * permission prompt, scan-counter reset or automatic uninstall is introduced here.
 */
object CatalogInstalledAppScanner {
    @Synchronized
    fun scan(
        ctx: Context,
        onMatch: (MalwareDatabase.MalwareEntry) -> Unit = { notify(ctx, it) }
    ): Int {
        if (!PrefsHelper.isProtectionEnabled(ctx)) return 0
        val packages = ThreatCatalog.snapshot()?.indicators?.keys ?: return 0
        val recorded = PrefsHelper.getThreats(ctx)
        val alreadyRecorded = (0 until recorded.length()).mapNotNull { index ->
            val record = recorded.getJSONObject(index)
            record.getString("packageName").takeIf { record.getString("status") != "removed" }
        }.toSet()
        var found = 0
        for (pkg in packages) {
            if (pkg == ctx.packageName || pkg in alreadyRecorded ||
                PrefsHelper.isThreatKept(ctx, pkg) ||
                MalwareDatabase.isSystemPrefix(pkg) ||
                MalwareDatabase.isTrustedApp(pkg) ||
                MalwareDatabase.isScanExempt(pkg)) continue
            // match reads the installed APK and requires the reviewed package + SHA-256.
            val entry = ThreatCatalog.match(ctx, pkg) ?: continue
            // A user may choose Manter while APK hashing is in progress.
            if (PrefsHelper.isThreatKept(ctx, pkg)) continue
            PrefsHelper.saveThreat(ctx, entry)
            found++
            // Denied/disabled notifications must not discard the detection or
            // stop checks of the remaining catalog entries.
            try {
                onMatch(entry)
            } catch (e: Exception) {
                Log.w("CatalogInstalledScan", "Não foi possível emitir o alerta", e)
            }
        }
        if (found > 0) {
            ctx.sendBroadcast(Intent("com.maguardian.security.THREAT_DETECTED")
                .setPackage(ctx.packageName))
        }
        return found
    }

    private fun notify(ctx: Context, entry: MalwareDatabase.MalwareEntry) {
        if (!PrefsHelper.isNotificationsEnabled(ctx)) return
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        val channelId = PopupDetectorService.CHANNEL_ID_THREAT
        // Reuse the existing threat channel without overriding the user's settings.
        if (manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(NotificationChannel(
                channelId, "Ameaças Detectadas", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alertas de pop-ups e adware detectados"
                enableVibration(true)
            })
        }
        val open = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val fullAccess = PrefsHelper.hasFullAccess(ctx)
        val notification = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_shield_alert)
            .setContentTitle("Ameaça detectada na base atualizada")
            .setContentText(if (fullAccess) "${entry.appName}: ${entry.description}"
                else "Abra o M&A Guardian para revisar a ameaça detectada.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // Opening the existing screen preserves Manter and subscription-gated uninstall.
        manager.notify(entry.packageName.hashCode(), notification)
    }
}
