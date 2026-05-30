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
import com.maguardian.security.util.TelemarketingDatabase

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

    // Palavras-chave que identificam centrais de cobrança/telemarketing pelo sistema
    private val spamKeywords = listOf(
        "cobran", "cobrança", "cobranca", "telemar", "speech", "crédito", "credito",
        "financ", "recupera", "cartão", "cartao", "vendas", "central de", "central_de",
        "atendimento", "promotor", "collector", "collect", "spam", "golpe",
        "fraude", "banco", "empréstimo", "emprestimo", "seguro", "oferta",
        "sac ", "sac-", "call center", "callcenter", "cobrança ativa",
        // Rótulos do Samsung SmartCall / sistema operacional
        "bloqueado", "informado", "suspeito", "spam call", "possível spam",
        "possivelmente", "desconhecido", "scam", "nuisance", "robocall"
    )

    override fun onScreenCall(callDetails: Call.Details) {
        val displayNameEarly = callDetails.callerDisplayName ?: ""
        if (displayNameEarly.isNotBlank()) {
            // Samsung/CNAM já preencheu callerDisplayName — triagem imediata
            performScreening(callDetails, displayNameEarly)
        } else {
            // callerDisplayName vazio: aguarda 1200ms para Samsung SmartCall / CNAM
            // completar a consulta assíncrona (responde tipicamente em < 1s).
            // Mantém-se bem abaixo do timeout do CallScreeningService (~5s).
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                performScreening(callDetails, callDetails.callerDisplayName ?: "")
            }, 1200L)
        }
    }

    private fun performScreening(callDetails: Call.Details, displayName: String) {
        val number = callDetails.handle?.schemeSpecificPart ?: ""
        var result = PhoneAnalyzer.analyze(number)

        // ── STIR/SHAKEN via reflection (evita referência direta à constante API 30+) ──
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            result = applyStirShaken(callDetails, result)
        }

        // ── Base local de telemarketing (número + nome da empresa) ──────────────
        // Verifica com callerDisplayName para pegar casos como "SPEECH", "COBRANCA"
        // que o Samsung preenche mesmo sem score no PhoneAnalyzer.
        val dbMatch = TelemarketingDatabase.check(number, displayName)
        if (dbMatch.matched) {
            val boosted = (result.score + dbMatch.confidence).coerceAtMost(100)
            result = result.copy(
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
                reasons = result.reasons + dbMatch.reason
            )
        }

        // ── callerDisplayName: label do sistema (Samsung SmartCall / CNAM / ANATEL) ──
        val isSystemLabeledSpam = displayName.isNotBlank() &&
            spamKeywords.any { displayName.contains(it, ignoreCase = true) }

        if (isSystemLabeledSpam) {
            result = result.copy(
                score   = result.score.coerceAtLeast(25),
                label   = if (result.score < 25) "Telemarketing / Cobrança" else result.label,
                emoji   = if (result.score < 25) "⚠️" else result.emoji,
                reasons = result.reasons +
                    "Identificado como \"$displayName\" pelo sistema — central de cobrança/telemarketing"
            )
        }

        val isSubscribed       = PrefsHelper.hasFullAccess(this)
        val blockTelemarketing = isSubscribed && PrefsHelper.isBlockTelemarketingEnabled(this)
        val isManuallyBlocked  = isSubscribed && PrefsHelper.isNumberBlocked(this, number)

        // Normaliza E.164 (+5504533...) → local (004533...) para checar prefixo 0303
        val localNumber = when {
            number.startsWith("+55") -> "0${number.substring(3)}"
            number.startsWith("55") && number.length >= 12 -> "0${number.substring(2)}"
            else -> number
        }
        val isAnatelTelemarketing = localNumber.startsWith("0303")

        // Bloqueio real de chamadas é exclusivo para assinantes
        val shouldBlock = when {
            isManuallyBlocked                              -> true  // lista negra manual (assinante)
            result.score >= 70 && isSubscribed             -> true  // golpe confirmado (assinante)
            isAnatelTelemarketing && blockTelemarketing    -> true  // 0303 ANATEL + toggle
            isSystemLabeledSpam   && blockTelemarketing    -> true  // label sistema + toggle
            result.score >= 25    && blockTelemarketing    -> true  // score ≥ 25 + toggle
            else                                           -> false
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

        // Botão "Bloquear" na notificação — exclusivo para assinantes
        if (number.isNotBlank() && PrefsHelper.hasFullAccess(this) &&
            !PrefsHelper.isNumberBlocked(this, number)) {
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
