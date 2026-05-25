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
        // NOTA: IDs com sufixo _v2 para forçar Android a criar canais novos com
        // a importância correta (canais antigos são imutáveis pelo app).
        const val CHANNEL_ALERT = "ma_call_alert_v2"  // Alta — suspeitos/golpes
        const val CHANNEL_SAFE  = "ma_call_safe_v2"   // Default — ligações seguras
        const val NOTIF_ID      = 2001
    }

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart ?: ""
        val result = PhoneAnalyzer.analyze(number)

        // Dispara a notificação ANTES de responder para que apareça como
        // heads-up antes que a tela de chamada tome conta da tela.
        showNotification(number, result)

        // Sempre deixa a ligação passar — nunca bloqueamos automaticamente.
        respondToCall(
            callDetails,
            CallResponse.Builder()
                .setDisallowCall(false)
                .setSilenceCall(false)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build()
        )
    }

    private fun showNotification(number: String, result: PhoneAnalyzer.Result) {
        val nm = getSystemService(NotificationManager::class.java)
        createChannels(nm)

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val displayNumber = if (number.isBlank()) "Número oculto" else number
        val isSafe = result.score < 25

        val (channelId, priority, color, title, body) = if (isSafe) {
            NotifConfig(
                CHANNEL_SAFE,
                NotificationCompat.PRIORITY_DEFAULT,
                0xFF22C55E.toInt(),
                "✅ Ligação Segura — M&A Guardian",
                "$displayNumber — Nenhum padrão de golpe detectado."
            )
        } else {
            val reasons = result.reasons.joinToString(" • ")
            NotifConfig(
                CHANNEL_ALERT,
                if (result.score >= 55) NotificationCompat.PRIORITY_MAX
                else NotificationCompat.PRIORITY_HIGH,
                if (result.score >= 55) 0xFFDC2626.toInt() else 0xFFF59E0B.toInt(),
                "${result.emoji} ${result.label} — Risco ${result.score}%",
                "$displayNumber\n$reasons"
            )
        }

        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_shield_alert)
            .setContentTitle(title)
            .setContentText(if (isSafe) body else displayNumber)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setColor(color)
            .setOnlyAlertOnce(false)
            .setTimeoutAfter(if (isSafe) 10_000L else 60_000L)
            .build()

        nm.notify(NOTIF_ID, notif)
    }

    private fun createChannels(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        // Canal de alerta — alta prioridade, vibração, pop-up
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

        // Canal seguro — prioridade padrão, sem som, aparece na gaveta
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SAFE,
                "Verificação de Ligação Segura",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Confirmação quando a ligação é verificada como segura"
                enableVibration(false)
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
