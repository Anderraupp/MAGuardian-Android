package com.maguardian.security.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import com.maguardian.security.R
import com.maguardian.security.receiver.BlockCallReceiver
import com.maguardian.security.ui.MainActivity
import com.maguardian.security.util.CallOverlayManager
import com.maguardian.security.util.CommunityBlocksApi
import com.maguardian.security.util.PhoneAnalyzer
import com.maguardian.security.util.PrefsHelper
import java.util.concurrent.Executors

/**
 * Serviço de monitoramento de ligações que funciona em TODAS as versões do Android.
 * - Android < 12 : PhoneStateListener (obtém número via READ_PHONE_STATE)
 * - Android 12+  : TelephonyCallback (API moderna, sem deprecações)
 * - Detecta ligações de contatos E de desconhecidos (diferente do CallScreeningService)
 */
class CallMonitorService : Service() {

    companion object {
        const val CHANNEL_PERSISTENT = "ma_protection_status"
        const val CHANNEL_CALL_ALERT = "ma_call_alert_v3"   // v3 força recriação com IMPORTANCE_HIGH
        const val CHANNEL_CALL_SAFE  = "ma_call_safe_v3"    // v3 força recriação com IMPORTANCE_HIGH
        const val NOTIF_PERSISTENT   = 3001
        const val NOTIF_CALL         = 2001

        fun start(context: Context) {
            val intent = Intent(context, CallMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallMonitorService::class.java))
        }
    }

    private var telephonyManager: TelephonyManager? = null
    private var legacyListener: PhoneStateListener? = null
    private var modernCallback: TelephonyCallback? = null
    // Android 12+ não fornece o número — CallScannerService assume análise/overlay
    private var numberUnavailable = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ exige que o tipo declarado no manifest seja passado aqui
            startForeground(
                NOTIF_PERSISTENT,
                buildPersistentNotif(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_PERSISTENT, buildPersistentNotif())
        }
        registerListener()

        // ── Sincroniza lista comunitária de bloqueios — apenas assinantes (6h/vez) ──
        if (PrefsHelper.hasFullAccess(this) && PrefsHelper.needsCommunitySync(this)) {
            CommunityBlocksApi.syncToLocal(this) { count ->
                android.util.Log.d("CallMonitorService", "Lista comunitária: $count números")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        unregisterListener()
    }

    // ── Registro do listener ──────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun registerListener() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ não fornece o número via TelephonyCallback — o CallScannerService
            // (que tem Call.Details) assumirá a análise e o overlay.
            numberUnavailable = true
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleState(state, null)
                }
            }
            modernCallback = cb
            try {
                telephonyManager?.registerTelephonyCallback(
                    Executors.newSingleThreadExecutor(), cb
                )
            } catch (e: SecurityException) {
                android.util.Log.w("CallMonitorService", "Sem permissão READ_PHONE_STATE: ${e.message}")
            }
        } else {
            // Android < 12 — PhoneStateListener (obtém número com READ_PHONE_STATE)
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleState(state, phoneNumber)
                }
            }
            legacyListener = listener
            @Suppress("DEPRECATION")
            telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    @Suppress("DEPRECATION")
    private fun unregisterListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modernCallback?.let { telephonyManager?.unregisterTelephonyCallback(it) }
        } else {
            legacyListener?.let {
                @Suppress("DEPRECATION")
                telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
            }
        }
    }

    // ── Lógica principal ─────────────────────────────────────────────────────

    private fun handleState(state: Int, number: String?) {
        val nm = getSystemService(NotificationManager::class.java)
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                if (numberUnavailable) {
                    // Android 12+: número indisponível via TelephonyCallback.
                    // CallScannerService tem o número real e mostra o overlay/notificação corretos.
                    // Evitamos o falso positivo de "Número oculto / Telemarketing".
                    return
                }

                // ── Verifica lista negra manual ANTES de analisar (apenas assinantes) ───
                if (number != null && PrefsHelper.hasFullAccess(this) &&
                    PrefsHelper.isNumberBlocked(this, number)) {
                    val notif = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_CALL_ALERT)
                        .setSmallIcon(R.drawable.ic_shield_alert)
                        .setContentTitle("🚫 Ligação bloqueada — M&A Guardian")
                        .setContentText("${PrefsHelper.normalizeForBlock(number)} · Na sua lista negra")
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                        .setAutoCancel(true)
                        .setColor(0xFFDC2626.toInt())
                        .setTimeoutAfter(10_000L)
                        .build()
                    nm.notify(NOTIF_CALL, notif)
                    return
                }

                val result = PhoneAnalyzer.analyze(number ?: "")
                CallOverlayManager.show(applicationContext, number ?: "", result)
                showCallNotif(number ?: "", result, nm)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                CallOverlayManager.dismiss(applicationContext)
                nm.cancel(NOTIF_CALL)
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                CallOverlayManager.dismiss(applicationContext)
                nm.cancel(NOTIF_CALL)
            }
        }
    }

    private fun showCallNotif(
        number: String,
        result: PhoneAnalyzer.Result,
        nm: NotificationManager
    ) {
        val displayNumber = when {
            number.isBlank() -> "Número oculto"
            else             -> number
        }
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val (channelId, priority, color, title, body) = when {
            result.score < 25 -> {
                val msg = "$displayNumber — ${result.label}"
                NotifConfig(
                    CHANNEL_CALL_SAFE,
                    NotificationCompat.PRIORITY_DEFAULT,
                    0xFF22C55E.toInt(),
                    "✅ Ligação Verificada — M&A Guardian",
                    msg
                )
            }
            result.score < 45 -> {
                // Telemarketing / Cobrança
                val reasons = result.reasons.joinToString(" • ")
                NotifConfig(
                    CHANNEL_CALL_ALERT,
                    NotificationCompat.PRIORITY_DEFAULT,
                    0xFF6B7280.toInt(),
                    "${result.emoji} ${result.label}",
                    "$displayNumber\n$reasons"
                )
            }
            result.score < 70 -> {
                // Número Muito Suspeito
                val reasons = result.reasons.joinToString(" • ")
                NotifConfig(
                    CHANNEL_CALL_ALERT,
                    NotificationCompat.PRIORITY_HIGH,
                    0xFFF59E0B.toInt(),
                    "${result.emoji} ${result.label} — Risco ${result.score}%",
                    "$displayNumber\n$reasons"
                )
            }
            else -> {
                // Possível Golpe
                val reasons = result.reasons.joinToString(" • ")
                NotifConfig(
                    CHANNEL_CALL_ALERT,
                    NotificationCompat.PRIORITY_MAX,
                    0xFFDC2626.toInt(),
                    "${result.emoji} ${result.label} — Risco ${result.score}%",
                    "$displayNumber\n$reasons"
                )
            }
        }

        // fullScreenIntent faz a notificação aparecer SOBRE a tela de chamada recebida
        val fullScreenIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_shield_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_CALL)   // CATEGORY_CALL tem prioridade máxima
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setFullScreenIntent(fullScreenIntent, result.score >= 45) // alta prioridade p/ suspeito/golpe
            .setColor(color)
            .setTimeoutAfter(if (result.score < 25) 15_000L else 90_000L)

        // Botão "Bloquear" — apenas para assinantes, ligações suspeitas e número visível
        if (result.score >= 25 && number.isNotBlank() &&
            PrefsHelper.hasFullAccess(this) &&
            !PrefsHelper.isNumberBlocked(this, number)
        ) {
            val blockIntent = PendingIntent.getBroadcast(
                this,
                number.hashCode(),
                Intent(BlockCallReceiver.ACTION_BLOCK_NUMBER).apply {
                    setPackage(packageName)
                    putExtra(BlockCallReceiver.EXTRA_NUMBER, number)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(
                R.drawable.ic_shield_alert,
                "🚫 Bloquear este número",
                blockIntent
            )
        }

        nm.notify(NOTIF_CALL, builder.build())
    }

    // ── Notificação persistente do serviço ────────────────────────────────────

    private fun buildPersistentNotif(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_PERSISTENT)
            .setSmallIcon(R.drawable.ic_shield_alert)
            .setContentTitle("🛡️ M&A Guardian — Proteção ativa")
            .setContentText("Monitorando ligações em tempo real")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setColor(0xFF22C55E.toInt())
            .build()
    }

    // ── Canais de notificação ─────────────────────────────────────────────────

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PERSISTENT,
                "Status da Proteção",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Indica que o M&A Guardian está monitorando ligações"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALL_ALERT,
                "Alertas de Ligações Suspeitas",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avisos de possíveis golpes e números suspeitos"
                enableVibration(true)
                setBypassDnd(true)  // aparece mesmo em Não Perturbe
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALL_SAFE,
                "Verificação de Ligação Segura",
                NotificationManager.IMPORTANCE_HIGH  // HIGH para aparecer como heads-up sobre a tela de chamada
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
