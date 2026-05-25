package com.maguardian.security.service

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.annotation.RequiresApi

/**
 * Mantém a ROLE_CALL_SCREENING oficial no Android 10+ (necessário para o botão "Ativar"
 * na MainActivity funcionar). As notificações de ligação são tratadas pelo
 * CallMonitorService, que funciona em todas as versões do Android.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class CallScannerService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        // Sempre permite a ligação — nunca bloqueamos automaticamente.
        // CallMonitorService cuida de todas as notificações.
        respondToCall(
            callDetails,
            CallResponse.Builder()
                .setDisallowCall(false)
                .setSilenceCall(false)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build()
        )
    }
}
