package com.maguardian.security.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.maguardian.security.R
import com.maguardian.security.ui.MainActivity
import com.maguardian.security.ui.SubscriptionActivity
import com.maguardian.security.util.PrefsHelper

class TrialAlertReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID_TRIAL = "maguardian_trial_alert"
        const val NOTIF_ID = 9001

        val MESSAGES = listOf(
            "Seu aparelho possui pontos de vulnerabilidade.",
            "A proteção em tempo real não está ativa.",
            "Seu celular pode estar exposto a ameaças.",
            "Apps podem acessar dados sem monitoramento."
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (PrefsHelper.hasFullAccess(context)) return

        ensureChannel(context)

        val idx = PrefsHelper.getTrialNotifCount(context) % MESSAGES.size
        val message = MESSAGES[idx]
        PrefsHelper.incrementTrialNotifCount(context)

        // Toque na notificação → abre o app
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context, 9001, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Botão "Seja Premium" → abre tela de assinatura direto
        val subscribeIntent = Intent(context, SubscriptionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val subscribePi = PendingIntent.getActivity(
            context, 9002, subscribeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_TRIAL)
            .setSmallIcon(R.drawable.ic_shield_alert)
            .setContentTitle("⚠️ M&A Guardian — Alerta de Segurança")
            .setContentText(message)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$message\n\nSeja Premium e proteja seu celular agora.")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .addAction(R.drawable.ic_shield_alert, "🔒 Seja Premium", subscribePi)
            .setColor(0xFFDC2626.toInt())
            .build()

        nm.notify(NOTIF_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID_TRIAL) != null) return
        NotificationChannel(
            CHANNEL_ID_TRIAL,
            "Alertas de Segurança",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Avisos sobre vulnerabilidades do dispositivo"
            enableVibration(true)
            nm.createNotificationChannel(this)
        }
    }
}
