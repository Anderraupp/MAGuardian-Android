package com.maguardian.security.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.install.model.UpdateAvailability
import com.maguardian.security.R

/** Avisos baseados na disponibilidade real para o usuário no Google Play. */
object AppUpdateNotifier {
    private const val CHANNEL = "ma_app_updates"
    private const val NOTIFICATION_ID = 4002
    private const val PREFS = "ma_app_update_notifications"
    private const val LAST_VERSION = "last_notified_version"

    fun handle(context: Context, info: AppUpdateInfo) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        @Suppress("DEPRECATION")
        val installedVersion = context.packageManager
            .getPackageInfo(context.packageName, 0).versionCode
        val lastVersion = prefs.getInt(LAST_VERSION, 0)
        if (lastVersion > 0 && installedVersion >= lastVersion) {
            nm.cancel(NOTIFICATION_ID)
        }
        if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return
        val availableVersion = info.availableVersionCode()
        if (availableVersion <= installedVersion || availableVersion <= lastVersion) return

        // Sem permissão, não registrar como avisado: tenta novamente na próxima consulta.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) return
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Atualizações do aplicativo", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Avisos de novas versões disponíveis no Google Play" }
        )
        if (nm.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return

        val playIntent = Intent(
            Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}")
        ).setPackage("com.android.vending")
        val destination = if (playIntent.resolveActivity(context.packageManager) != null) {
            playIntent
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}"))
        }
        destination.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val openStore = PendingIntent.getActivity(
            context, NOTIFICATION_ID, destination,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield_alert)
            .setContentTitle("Atualização disponível — M&A Guardian")
            .setContentText("Toque para atualizar pela Play Store e receber as melhorias do aplicativo.")
            .setContentIntent(openStore)
            .addAction(R.drawable.ic_shield_alert, "Atualizar na Play Store", openStore)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIFICATION_ID, notification)
            prefs.edit().putInt(LAST_VERSION, availableVersion).apply()
        } catch (e: SecurityException) {
            Log.w("AppUpdateNotifier", "Permissão de notificação indisponível", e)
        }
    }
}
