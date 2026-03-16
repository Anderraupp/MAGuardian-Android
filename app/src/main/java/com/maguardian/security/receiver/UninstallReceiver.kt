package com.maguardian.security.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

class UninstallReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_UNINSTALL = "com.maguardian.security.ACTION_UNINSTALL"
        const val EXTRA_PACKAGE = "packageName"
        const val TAG = "UninstallReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_UNINSTALL) return

        val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        Log.i(TAG, "Desinstalando: $packageName")

        val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$packageName")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(uninstallIntent)
    }
}
