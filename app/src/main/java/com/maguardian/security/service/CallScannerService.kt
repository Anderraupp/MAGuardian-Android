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
import com.maguardian.security.receiver.BlockCallReceiver
import com.maguardian.security.ui.MainActivity
import com.maguardian.security.util.CallOverlayManager
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
        const val CHANNEL_BLOCKED  = "ma_call_blocked"
        const val CHANNEL_ANALYSIS = "ma_call_analysis_v2"
        const val NOTIF_BLOCKED    = 2002
        const val NOTIF_ANALYSIS   = 2003
    }

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart ?: ""
        val result = PhoneAnalyzer.analyze(number)
        val blockTelemarketing = PrefsHelper.isBlockTelemarketingEnabled(this)
        val isManuallyBlocked  = PrefsHelper.isNumberBlocked(this, number)

        val shouldBlock = when {
            isManuallyBlocked                        -> true
            result.score >= 70                       -> true
            result.score >= 25 && blockTelemarketing -> true
            else                                     -> false
        }

        respondToCall(
            callDetails,
            CallResponse.Builder()
                .setDisallowCall(shouldBlock)
                .setSilenceCall(shouldBlock)
                .setSkipCallLog(false)
                .setSkipNotification(shouldBlock)
                .build()
        )

        // ── Overlay flutuante sobre a tela de chamada (número real do Call.Details) ──
        CallOverlayManager.show(applicationContext, number, result)

        // ── Notificação na barra ──────────────────────────────────────────────────
        if (shouldBlock) showBlockedNotification(number, result)
        else             showAnalysisNotification(number, result)
    }

    // ── Notificação: ligação bloqueada ────────────────────────────────────────
    private fun showBlockedNotification(number: String, result: PhoneAnalyzer.Result) {
        val nm = ensureChannels()
        val displayNumber = if (number.isBlank()) "Número oculto" else number
        val openIntent = openAppIntent(0)

        val notif = NotificationCompat.Builder(this, CHANNEL_BLOCKED)
            .setSmallIcon(R.drawable.ic_shield_alert)
            .setContentTitle("🚫 Ligação bloqueada — M&A Guardian")
            .setContentText("$displayNumber · ${result.label}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setColor(0xFFDC2626.toInt())
            .setTimeoutAfter(30_000L)
            .build()

        nm.notify(NOTIF_BLOCKED, notif)
    }

    // ── Notificação: análise da ligação (não bloqueada) ───────────────────────
    private fun showAnalysisNotification(number: String, result: PhoneAnalyzer.Result) {
        val nm = ensureChannels()
        val displayNumber = if (number.isBlank()) "Número oculto" else number
        val openIntent = openAppIntent(1)

        // Cor e texto conforme score
        val (color, title) = when {
            result.score >= 45 -> 0xFFF59E0B.toInt() to "${result.emoji} ${result.label} — Risco ${result.score}%"
            result.score >= 25 -> 0xFF6B7280.toInt() to "${result.emoji} ${result.label}"
            else               -> 0xFF22C55E.toInt() to "✅ Ligação verificada — segura"
        }

        val bodyLines = buildString {
            append(displayNumber)
            if (result.reasons.isNotEmpty() && result.score >= 25)
                append("\n" + result.reasons.take(2).joinToString(" • "))
        }

        // Botão "Bloquear" para chamadas suspeitas ainda não bloqueadas manualmente
        val builder = NotificationCompat.Builder(this, CHANNEL_ANALYSIS)
            .setSmallIcon(R.drawable.ic_shield_alert)
            .setContentTitle(title)
            .setContentText(bodyLines)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyLines))
            .setPriority(if (result.score >= 25) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setColor(color)
            .setTimeoutAfter(if (result.score < 25) 15_000L else 90_000L)

        if (result.score >= 25 && number.isNotBlank() && !PrefsHelper.isNumberBlocked(this, number)) {
            val blockIntent = PendingIntent.getBroadcast(
                this,
                number.hashCode(),
                Intent(BlockCallReceiver.ACTION_BLOCK_NUMBER).apply {
                    setPackage(packageName)
                    putExtra(BlockCallReceiver.EXTRA_NUMBER, number)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(R.drawable.ic_shield_alert, "🚫 Bloquear este número", blockIntent)
        }

        nm.notify(NOTIF_ANALYSIS, builder.build())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun ensureChannels(): NotificationManager {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_BLOCKED, "Ligações Bloqueadas", NotificationManager.IMPORTANCE_HIGH
            ).apply { enableVibration(true) })
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ANALYSIS, "Análise de Ligações", NotificationManager.IMPORTANCE_HIGH
            ).apply { enableVibration(false); setBypassDnd(true) })
        }
        return nm
    }

    private fun openAppIntent(reqCode: Int) = PendingIntent.getActivity(
        this, reqCode,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
