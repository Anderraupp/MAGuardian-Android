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
        var result = PhoneAnalyzer.analyze(number)

        // ── STIR/SHAKEN via reflection (evita referência direta à constante API 30+) ──
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            result = applyStirShaken(callDetails, result)
        }

        // ── callerDisplayName: detecta labels de cobrança/telemarketing do sistema ──
        // A Samsung (e outras) preenche callerDisplayName com o nome/categoria da empresa
        // detectada — ex: "SPEECH", "COBRANCA", "TELEMARKETING". Usamos isso para boostar
        // o score mesmo quando o formato do número é BR válido (ex: DDD 45, 12 dígitos).
        val displayName = callDetails.callerDisplayName ?: ""
        if (displayName.isNotBlank()) {
            val keywords = listOf(
                "cobran", "cobrança", "telemar", "speech", "crédito", "credito",
                "financ", "recupera", "cartão", "cartao", "vendas", "central de",
                "atendimento", "promotor", "collector", "collect"
            )
            if (keywords.any { displayName.contains(it, ignoreCase = true) }) {
                val boosted = (result.score + 30).coerceAtMost(100)
                result = result.copy(
                    score   = boosted,
                    label   = when { boosted >= 70 -> "Possível Golpe"; boosted >= 45 -> "Número Muito Suspeito"; else -> "Telemarketing / Cobrança" },
                    emoji   = when { boosted >= 70 -> "🚨"; boosted >= 45 -> "🔴"; else -> "⚠️" },
                    reasons = result.reasons +
                        "Identificado como \"$displayName\" pelo sistema — central de atendimento/cobrança"
                )
            }
        }

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

        // ── Overlay SYSTEM_ALERT_WINDOW (se permissão concedida) ──────────────────
        CallOverlayManager.show(applicationContext, number, result)

        // ── Notificação na barra + fullScreenIntent → CallAlertActivity ───────────
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

        // ── fullScreenIntent → CallAlertActivity (aparece sobre tela de chamada) ──
        val alertIntent = Intent(this, com.maguardian.security.ui.CallAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_HISTORY
            putExtra(com.maguardian.security.ui.CallAlertActivity.EXTRA_NUMBER, number)
            putExtra(com.maguardian.security.ui.CallAlertActivity.EXTRA_SCORE,  result.score)
            putExtra(com.maguardian.security.ui.CallAlertActivity.EXTRA_LABEL,  result.label)
            putExtra(com.maguardian.security.ui.CallAlertActivity.EXTRA_EMOJI,  result.emoji)
            putStringArrayListExtra(
                com.maguardian.security.ui.CallAlertActivity.EXTRA_REASONS,
                ArrayList(result.reasons)
            )
        }
        val fullScreenPi = PendingIntent.getActivity(
            this, number.hashCode() + 1,
            alertIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ANALYSIS)
            .setSmallIcon(R.drawable.ic_shield_alert)
            .setContentTitle(title)
            .setContentText(bodyLines)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyLines))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(fullScreenPi)
            .setFullScreenIntent(fullScreenPi, true)   // lança CallAlertActivity sobre chamada
            .setColor(color)
            .setTimeoutAfter(if (result.score < 25) 15_000L else 90_000L)

        // Botão "Bloquear" na notificação (para números suspeitos)
        if (number.isNotBlank() && !PrefsHelper.isNumberBlocked(this, number)) {
            val blockPi = PendingIntent.getBroadcast(
                this,
                number.hashCode(),
                Intent(BlockCallReceiver.ACTION_BLOCK_NUMBER).apply {
                    setPackage(packageName)
                    putExtra(BlockCallReceiver.EXTRA_NUMBER, number)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(R.drawable.ic_shield_alert, "🚫 Bloquear", blockPi)
        }

        nm.notify(NOTIF_ANALYSIS, builder.build())
    }

    // ── STIR/SHAKEN via reflection ────────────────────────────────────────────
    // Usa reflection + literal inteiro para evitar "Unresolved reference: VERIFICATION_STATUS_FAILED"
    // que ocorre quando o SDK local não tem o stub da constante API 30+ indexado.
    // Valor 2 = Call.Details.VERIFICATION_STATUS_FAILED (documentado em developer.android.com)
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun applyStirShaken(
        callDetails: Call.Details,
        result: PhoneAnalyzer.Result
    ): PhoneAnalyzer.Result {
        return try {
            val verStatus = callDetails.javaClass
                .getMethod("getCallerNumberVerificationStatus")
                .invoke(callDetails) as? Int ?: return result
            val FAILED = 2 // Call.Details.VERIFICATION_STATUS_FAILED
            if (verStatus != FAILED) return result
            val boosted = (result.score + 35).coerceAtMost(100)
            result.copy(
                score   = boosted,
                label   = when {
                    boosted >= 70 -> "Possível Golpe"
                    boosted >= 45 -> "Número Muito Suspeito"
                    else          -> "Telemarketing / Cobrança"
                },
                emoji   = when {
                    boosted >= 70 -> "🚨"
                    boosted >= 45 -> "🔴"
                    else          -> "⚠️"
                },
                reasons = result.reasons +
                    "Verificação de identidade (STIR/SHAKEN) falhou — número possivelmente falsificado pela operadora"
            )
        } catch (_: Exception) {
            result // fallback gracioso — dispositivo/operadora sem STIR/SHAKEN
        }
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
