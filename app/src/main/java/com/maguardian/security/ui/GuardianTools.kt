package com.maguardian.security.ui

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.maguardian.security.R
import com.maguardian.security.service.CallMonitorService
import com.maguardian.security.util.PrefsHelper

internal object GuardianTools {
    private var synchronizing = false
    fun bind(activity: MainActivity, permissions: () -> Unit, requestCalls: () -> Unit, canChange: () -> Boolean) {
        fun action(id: Int, block: () -> Unit) = activity.findViewById<View>(id).setOnClickListener { block() }
        action(R.id.btnToolsBack) { GuardianNavigation.selectMain(activity, false) }
        action(R.id.btnToolsPermissions, permissions)
        action(R.id.btnToolsNotifications) {
            GuardianExternal.open(activity, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName), "Configurações → Apps → M&A Guardian → Notificações")
        }
        action(R.id.btnAppearance) { GuardianAppearance.showDesigner(activity, canChange) }
        action(R.id.btnToolsTerms) { GuardianExternal.web(activity, GuardianExternal.TERMS) }
        action(R.id.btnToolsPrivacy) { GuardianExternal.web(activity, GuardianExternal.PRIVACY) }
        action(R.id.btnScanHistory) { activity.startActivity(Intent(activity, ScanHistoryActivity::class.java)) }
        val block = activity.findViewById<SwitchCompat>(R.id.switchBlockTelemarketing)
        block.setOnCheckedChangeListener { _, enabled ->
            if (synchronizing) return@setOnCheckedChangeListener
            if (enabled && !PrefsHelper.hasFullAccess(activity)) {
                refresh(activity)
                activity.startActivity(Intent(activity, SubscriptionActivity::class.java))
            } else {
                PrefsHelper.setBlockTelemarketingEnabled(activity, enabled)
                if (enabled && !screeningAvailable(activity)) requestCalls()
                refresh(activity)
            }
        }
        val alerts = activity.findViewById<SwitchCompat>(R.id.switchCallAlerts)
        alerts.setOnCheckedChangeListener { _, enabled ->
            if (synchronizing) return@setOnCheckedChangeListener
            PrefsHelper.setCallAlertsEnabled(activity, enabled)
            if (enabled) {
                if (!alertsAvailable(activity)) requestCalls()
                else runCatching { CallMonitorService.start(activity) }.onFailure {
                    PrefsHelper.setCallAlertsEnabled(activity, false)
                    AlertDialog.Builder(activity).setTitle("Não foi possível ativar")
                        .setMessage("O Android não permitiu iniciar os alertas. Revise as permissões e tente novamente.")
                        .setPositiveButton("Revisar") { _, _ -> permissions() }.setNegativeButton("Voltar", null).show()
                }
            } else {
                activity.getSystemService(NotificationManager::class.java).cancel(CallMonitorService.NOTIF_CALL)
                com.maguardian.security.util.CallOverlayManager.dismiss(activity)
            }
            refresh(activity)
        }
        refresh(activity)
    }
    private fun screeningAvailable(ctx: AppCompatActivity) = Build.VERSION.SDK_INT >= 29 &&
        ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    private fun alertsAvailable(ctx: AppCompatActivity): Boolean {
        val phone = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val notifications = androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        val overlay = Settings.canDrawOverlays(ctx)
        return phone && (notifications || overlay) && (Build.VERSION.SDK_INT < 29 || screeningAvailable(ctx))
    }
    fun refresh(activity: MainActivity) {
        synchronizing = true
        try {
            val desiredBlock = PrefsHelper.isBlockTelemarketingEnabled(activity)
            val blocking = desiredBlock && PrefsHelper.hasFullAccess(activity) && screeningAvailable(activity)
            activity.findViewById<SwitchCompat>(R.id.switchBlockTelemarketing).isChecked = blocking
            activity.findViewById<TextView>(R.id.tvBlockTelemarketingStatus).text = when {
                blocking -> "Ativado"
                desiredBlock && !PrefsHelper.hasFullAccess(activity) -> "Disponível com Premium."
                desiredBlock -> "Pendente: autorize a triagem de chamadas no Android."
                else -> "Desativado"
            }
            val desiredAlerts = PrefsHelper.isCallAlertsEnabled(activity)
            activity.findViewById<SwitchCompat>(R.id.switchCallAlerts).isChecked = desiredAlerts && alertsAvailable(activity)
            activity.findViewById<TextView>(R.id.tvCallAlertsStatus).text = when {
                !desiredAlerts -> "Desativado"
                !alertsAvailable(activity) -> "Pendente: revise as permissões de telefone e a triagem."
                !androidx.core.app.NotificationManagerCompat.from(activity).areNotificationsEnabled() &&
                    !Settings.canDrawOverlays(activity) -> "Sem canal de alerta autorizado. Ative notificações ou a exibição sobre apps."
                else -> "Ativado conforme as permissões do Android."
            }
        } finally { synchronizing = false }
    }
}
