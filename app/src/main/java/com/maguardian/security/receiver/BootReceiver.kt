package com.maguardian.security.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
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

        if (!PrefsHelper.isProtectionEnabled(context)) {
            Log.i(TAG, "Proteção desativada pelo usuário — ignorando boot")
            return
        }

        val serviceIntent = Intent(context, PopupDetectorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
