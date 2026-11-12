package com.maguardian.security.util

import android.content.Context
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/** Local inbox. Update notices come only from a successful Google Play response. */
object AppNoticeCenter {
    enum class Destination { SUBSCRIPTION, PLAY_STORE }
    data class Notice(val id: String, val title: String, val body: String, val destination: Destination)

    private fun prefs(context: Context) = context.getSharedPreferences("guardian_notice_center", Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    private fun installedVersion(context: Context) =
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode

    fun recordPlayInfo(context: Context, info: AppUpdateInfo) {
        val available = info.updateAvailability()
        val version = info.availableVersionCode()
        val pending = available == UpdateAvailability.UPDATE_AVAILABLE ||
            available == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ||
            info.installStatus() == InstallStatus.DOWNLOADED
        when {
            pending && version > installedVersion(context) ->
                prefs(context).edit().putInt("play_version", version).apply()
            available == UpdateAvailability.UPDATE_NOT_AVAILABLE ||
                (pending && version <= installedVersion(context)) ->
                prefs(context).edit().remove("play_version").apply()
            // UNKNOWN / failed checks must not invent an update or discard confirmed state.
        }
    }

    fun notices(context: Context): List<Notice> = buildList {
        if (!PrefsHelper.isSubscriptionActive(context)) {
            add(Notice("subscription", "Seja Premium",
                "Conheça a assinatura do M&A Guardian e os recursos de proteção disponíveis.",
                Destination.SUBSCRIPTION))
        }
        val version = prefs(context).getInt("play_version", 0)
        if (version > installedVersion(context)) {
            add(Notice("play_$version", "Atualização disponível",
                "Uma nova versão está disponível para você na Play Store. Toque para atualizar.",
                Destination.PLAY_STORE))
        } else if (version > 0) {
            prefs(context).edit().remove("play_version").apply()
        }
    }

    fun unreadCount(context: Context): Int =
        notices(context).count { !prefs(context).getBoolean("seen_${it.id}", false) }

    fun markSeen(context: Context, notices: List<Notice>) {
        prefs(context).edit().apply {
            notices.forEach { putBoolean("seen_${it.id}", true) }
        }.apply()
    }
}
