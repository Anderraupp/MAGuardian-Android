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
import com.maguardian.security.util.PrefsHelper

/**
 * Mantém a ROLE_CALL_SCREENING oficial no Android 10+.
 * Quando "Bloquear Telemarketing" está ativo, rejeita silenciosamente ligações
 * com score 25–69% (Telemarketing/Cobrança e Muito Suspeito).
 * Golpes confirmados (70%+) são sempre bloqueados enquanto a role estiver ativa.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class CallScannerService : CallScreeningService() {

    companion object {
        const val CHANNEL_BLOCKED = "ma_call_blocked"
        const val NOTIF_BLOCKED   = 2002
    }

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart ?: ""
        val result = PhoneAnalyzer.analyze(number)
        val blockTelemarketing = PrefsHelper.isBlockTelemarketingEnabled(this)

        val isManuallyBlocked = PrefsHelper.isNumberBlocked(this, number)

        val shouldBlock = when {
            isManuallyBlocked                        -> true   // Bloqueado manualmente pelo usuário
            result.score >= 70                       -> true   // Golpe — bloqueia sempre
            result.score >= 25 && blockTelemarketing -> true   // Telemarketing/Cobrança — só se ativo
            else                                     -> false
        }

        respondToCall(
            callDetails,
            CallResponse.Builder()
                .setDisallowCall(shouldBlock)
                .setSilenceCall(shouldBlock)
                .setSkipCallLog(false)
                .setSkipNotification(shouldBlock) // true = suprime toque/UI quando bloqueia
                .build()
        )

        if (shouldBlock) showBlockedNotification(number, result)
    }

    private fun showBlockedNotification(number: String, result: PhoneAnalyzer.Result) {
        val nm = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_BLOCKED,
                    "Ligações Bloqueadas",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { enableVibration(false) }
            )
        }

        val displayNumber = if (number.isBlank()) "Número oculto" else number
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_BLOCKED)
            .setSmallIcon(R.drawable.ic_shield_alert)
            .setContentTitle("🚫 Ligação bloqueada — M&A Guardian")
            .setContentText("$displayNumber · ${result.label}")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setColor(0xFF6B7280.toInt())
            .setTimeoutAfter(30_000L)
            .build()

        nm.notify(NOTIF_BLOCKED, notif)
    }
}
