package com.maguardian.security.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.maguardian.security.R
import com.maguardian.security.ui.MainActivity
import com.maguardian.security.util.PhoneAnalyzer

@RequiresApi(Build.VERSION_CODES.Q)
class CallScannerService : CallScreeningService() {

    companion object {
        const val CHANNEL_ALERT   = "ma_call_alert"   // Alta prioridade — suspeitos/golpes
        const val CHANNEL_SAFE    = "ma_call_safe"    // Baixa prioridade — ligações seguras
        const val NOTIF_ID        = 2001
    }

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart ?: ""
        val result = PhoneAnalyzer.analyze(number)

        // Sempre deixa a ligação passar — nunca bloqueamos automaticamente
        respondToCall(
            callDetails,
            CallResponse.Builder()
                .setDisallowCall(false)
                .setSilenceCall(false)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build()
        )

        // Mostra notificação para TODAS as ligações (feedback ao usuário)
        showNotification(number, result)
    }

    private fun showNotification(number: String, result: PhoneAnalyzer.Result) {
        val nm = getSystemService(NotificationManager::class.java)

        createChannels(nm)

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val displayNumber = if (number.isBlank()) "Número oculto" else number

        val isSafe = result.score < 25

        val (channelId, priority, color, title, bodyText) = if (isSafe) {
            val body = "Nenhum padrão de golpe detectado nesta ligação."
            NotifConfig(
                CHANNEL_SAFE,
                NotificationCompat.PRIORITY_LOW,
                0xFF22C55E.toInt(),
                "${result.emoji} ${result.label}",
                body
            )
        } else {
            val reasons = result.reasons.joinToString(" • ")
            NotifConfig(
                CHANNEL_ALERT,
                if (result.score >= 55) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH,
                if (result.score >= 55) 0xFFDC2626.toInt() else 0xFFF59E0B.toInt(),
                "${result.emoji} ${result.label} — Risco ${result.score}%",
                "$displayNumber\n$reasons"
            )
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_shield_alert)
            .setContentTitle(title)
            .setContentText(if (isSafe) bodyText else displayNumber)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setColor(color)
            .setTimeoutAfter(if (isSafe) 8_000L else 30_000L) // Segura some em 8s, alerta fica 30s
            .build()

        nm.notify(NOTIF_ID, builder)
    }

    private fun createChannels(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                "Alertas de Ligações Suspeitas",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avisos de possíveis golpes e números suspeitos"
                enableVibration(true)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SAFE,
                "Confirmação de Ligação Segura",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Confirmação silenciosa quando a ligação é verificada como segura"
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    private data class NotifConfig(
        val channelId: String,
        val priority: Int,
        val color: Int,
        val title: String,
        val bodyText: String
    )
}
