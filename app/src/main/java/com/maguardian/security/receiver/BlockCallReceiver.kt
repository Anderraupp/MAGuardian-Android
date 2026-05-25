package com.maguardian.security.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.maguardian.security.util.PrefsHelper

/**
 * Recebe a ação "Bloquear este número" disparada pelo botão na notificação
 * de Telemarketing / Cobrança / Suspeito.
 */
class BlockCallReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_BLOCK_NUMBER = "com.maguardian.security.BLOCK_NUMBER"
        const val EXTRA_NUMBER        = "extra_number"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BLOCK_NUMBER) return

        val number = intent.getStringExtra(EXTRA_NUMBER) ?: return
        if (number.isBlank()) return

        PrefsHelper.blockNumber(context, number)

        // Cancela a notificação que gerou o botão
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(2001) // NOTIF_CALL do CallMonitorService

        Toast.makeText(
            context,
            "🚫 Número $number bloqueado com sucesso",
            Toast.LENGTH_LONG
        ).show()
    }
}
