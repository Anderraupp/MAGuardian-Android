package com.maguardian.security.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.maguardian.security.service.CallMonitorService
import com.maguardian.security.service.PopupDetectorService
import com.maguardian.security.util.PrefsHelper

class BootReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return

        Log.i(TAG, "Boot detectado — iniciando M&A Guardian automaticamente")

        // CallMonitorService é iniciado SEMPRE que a role de triagem estiver ativa,
        // independentemente da assinatura. A proteção de ligações é uma feature separada.
        val callScannerRoleActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(android.app.role.RoleManager::class.java)
            roleManager?.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING) == true
        } else false

        if (callScannerRoleActive) {
            Log.i(TAG, "Role de triagem ativa — iniciando CallMonitorService")
            CallMonitorService.start(context)
        }

        // Os demais serviços (pop-up, scan) dependem da assinatura/proteção geral
        if (!PrefsHelper.isProtectionEnabled(context)) {
            Log.i(TAG, "Proteção geral desativada — ignorando demais serviços no boot")
            return
        }

        // Inicia o serviço de detecção de pop-ups
        val popupIntent = Intent(context, PopupDetectorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(popupIntent)
        } else {
            context.startService(popupIntent)
        }

        // Garante que o CallMonitorService também sobe com a proteção geral
        if (!callScannerRoleActive) {
            CallMonitorService.start(context)
        }
    }
}
