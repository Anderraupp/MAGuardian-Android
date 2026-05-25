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
        const val CHANNEL_ID = "ma_call_scan"
        const val NOTIF_ID = 2001
    }

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart ?: ""
        val result = PhoneAnalyzer.analyze(number)

        // Always allow the call — we never auto-block (keeps Google happy, respects user choice)
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setSilenceCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()

        respondToCall(callDetails, response)

        // If suspicious, fire a heads-up notification while the phone rings
        if (result.score >= 25) {
            showWarningNotification(number, result)
        }
    }

    private fun showWarningNotification(number: String, result: PhoneAnalyzer.Result) {
        val nm = getSystemService(NotificationManager::class.java)

        // Create channel if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Alertas de Ligações Suspeitas",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "M&A Guardian detectou ligação suspeita"
                enableVibration(true)
            }
            nm.createNotificationChannel(ch)
        }

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val displayNumber = if (number.isBlank()) "Número oculto" else number
        val reasonText = result.reasons.joinToString(" • ")

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield_alert)
            .setContentTitle("${result.label} detectado!")
            .setContentText("$displayNumber — Risco: ${result.score}%")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$displayNumber\n\nRisco: ${result.score}%\n$reasonText")
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setColor(if (result.score >= 55) 0xFFDC2626.toInt() else 0xFFF59E0B.toInt())
            .build()

        nm.notify(NOTIF_ID, notif)
    }
}
