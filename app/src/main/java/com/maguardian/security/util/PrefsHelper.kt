package com.maguardian.security.util

import android.content.Context
import com.maguardian.security.data.MalwareDatabase
import org.json.JSONArray
import org.json.JSONObject

object PrefsHelper {

    private const val PREFS_NAME = "maguardian_prefs"
    private const val KEY_PROTECTION_ENABLED = "protection_enabled"
    private const val KEY_AUTO_REMOVE = "auto_remove"
    private const val KEY_NOTIFICATIONS = "notifications"
    private const val KEY_THREATS = "threats"
    private const val KEY_TOTAL_FOUND = "total_threats_found"
    private const val KEY_TOTAL_REMOVED = "total_threats_removed"
    private const val KEY_LAST_SCAN = "last_scan"
    private const val KEY_SCAN_COUNT = "scan_count"
    private const val KEY_NOTIF_ASKED = "notif_permission_asked"
    private const val KEY_LAST_STATS_RESET = "last_stats_reset"

    private const val STATS_RESET_INTERVAL_MS = 12 * 60 * 60 * 1000L // 12 horas

    fun hasAskedNotifPermission(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_NOTIF_ASKED, false)

    fun setNotifPermissionAsked(ctx: Context) =
        prefs(ctx).edit().putBoolean(KEY_NOTIF_ASKED, true).apply()

    fun isProtectionEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_PROTECTION_ENABLED, true)

    fun setProtectionEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_PROTECTION_ENABLED, enabled).apply()

    fun isAutoRemoveEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTO_REMOVE, false)

    fun setAutoRemoveEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AUTO_REMOVE, enabled).apply()

    fun isNotificationsEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_NOTIFICATIONS, true)

    fun getTotalThreatsFound(ctx: Context): Int =
        prefs(ctx).getInt(KEY_TOTAL_FOUND, 0)

    fun getTotalThreatsRemoved(ctx: Context): Int =
        prefs(ctx).getInt(KEY_TOTAL_REMOVED, 0)

    fun getLastScan(ctx: Context): Long =
        prefs(ctx).getLong(KEY_LAST_SCAN, 0L)

    fun setLastScan(ctx: Context, time: Long) =
        prefs(ctx).edit().putLong(KEY_LAST_SCAN, time).apply()

    fun getScanCount(ctx: Context): Int =
        prefs(ctx).getInt(KEY_SCAN_COUNT, 0)

    fun incrementScanCount(ctx: Context) {
        val count = prefs(ctx).getInt(KEY_SCAN_COUNT, 0) + 1
        prefs(ctx).edit().putInt(KEY_SCAN_COUNT, count).apply()
    }

    fun saveThreat(ctx: Context, malware: MalwareDatabase.MalwareEntry) {
        val prefs = prefs(ctx)
        val threatsJson = prefs.getString(KEY_THREATS, "[]") ?: "[]"
        val arr = JSONArray(threatsJson)

        // Evita duplicatas: só salva se o package ainda não está na lista
        for (i in 0 until arr.length()) {
            val existing = arr.getJSONObject(i)
            if (existing.getString("packageName") == malware.packageName &&
                existing.getString("status") != "removed") {
                return
            }
        }

        val obj = JSONObject().apply {
            put("packageName", malware.packageName)
            put("appName", malware.appName)
            put("threatType", malware.threatType)
            put("severity", malware.severity)
            put("description", malware.description)
            put("detectedAt", System.currentTimeMillis())
            put("status", "detected")
        }
        arr.put(obj)

        val total = prefs.getInt(KEY_TOTAL_FOUND, 0) + 1
        prefs.edit()
            .putString(KEY_THREATS, arr.toString())
            .putInt(KEY_TOTAL_FOUND, total)
            .apply()
    }

    fun markThreatRemoved(ctx: Context, packageName: String) {
        val prefs = prefs(ctx)
        val threatsJson = prefs.getString(KEY_THREATS, "[]") ?: "[]"
        val arr = JSONArray(threatsJson)
        val updated = JSONArray()

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getString("packageName") == packageName) {
                obj.put("status", "removed")
                obj.put("removedAt", System.currentTimeMillis())
            }
            updated.put(obj)
        }

        val totalRemoved = prefs.getInt(KEY_TOTAL_REMOVED, 0) + 1
        prefs.edit()
            .putString(KEY_THREATS, updated.toString())
            .putInt(KEY_TOTAL_REMOVED, totalRemoved)
            .apply()
    }

    fun getThreats(ctx: Context): JSONArray {
        val json = prefs(ctx).getString(KEY_THREATS, "[]") ?: "[]"
        return JSONArray(json)
    }

    fun clearThreats(ctx: Context) =
        prefs(ctx).edit().putString(KEY_THREATS, "[]").apply()

    // ── Reset automático a cada 12 horas ──

    fun getLastStatsReset(ctx: Context): Long =
        prefs(ctx).getLong(KEY_LAST_STATS_RESET, 0L)

    /**
     * Zera contadores de ameaças, remoções, escaneamentos e histórico de ameaças detectadas.
     * Preserva configurações do usuário (proteção ativa, notificações, etc.).
     */
    fun resetStats(ctx: Context) {
        prefs(ctx).edit()
            .putInt(KEY_TOTAL_FOUND, 0)
            .putInt(KEY_TOTAL_REMOVED, 0)
            .putInt(KEY_SCAN_COUNT, 0)
            .putLong(KEY_LAST_SCAN, 0L)
            .putString(KEY_THREATS, "[]")
            .putLong(KEY_LAST_STATS_RESET, System.currentTimeMillis())
            .apply()
    }

    /**
     * Verifica se já passaram 12 horas desde o último reset e, se sim, reseta as estatísticas.
     * Retorna true se o reset foi executado (para o serviço limpar seu estado interno também).
     */
    fun maybeAutoReset(ctx: Context): Boolean {
        val lastReset = getLastStatsReset(ctx)
        val now = System.currentTimeMillis()
        if (lastReset == 0L || (now - lastReset) >= STATS_RESET_INTERVAL_MS) {
            resetStats(ctx)
            return true
        }
        return false
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
