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
    private const val KEY_LAST_STATS_RESET   = "last_stats_reset"
    private const val KEY_SUBSCRIPTION_ACTIVE = "subscription_active"
    private const val KEY_TRIAL_NOTIF_COUNT  = "trial_notif_count"
    private const val KEY_LAST_TRIAL_POPUP   = "last_trial_popup"
    private const val KEY_TRIAL_ALARM_SET        = "trial_alarm_set"
    private const val KEY_BLOCK_TELEMARKETING    = "block_telemarketing"

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

    fun isBlockTelemarketingEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_BLOCK_TELEMARKETING, false)

    fun setBlockTelemarketingEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_BLOCK_TELEMARKETING, enabled).apply()

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

    // ── Assinatura Google Play ──

    fun isSubscriptionActive(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SUBSCRIPTION_ACTIVE, false)

    fun setSubscriptionActive(ctx: Context, active: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SUBSCRIPTION_ACTIVE, active).apply()

    fun hasFullAccess(ctx: Context): Boolean =
        isSubscriptionActive(ctx)

    // ── Alertas para não-assinantes ──

    fun getTrialNotifCount(ctx: Context): Int =
        prefs(ctx).getInt(KEY_TRIAL_NOTIF_COUNT, 0)

    fun incrementTrialNotifCount(ctx: Context) {
        val count = prefs(ctx).getInt(KEY_TRIAL_NOTIF_COUNT, 0) + 1
        prefs(ctx).edit().putInt(KEY_TRIAL_NOTIF_COUNT, count).apply()
    }

    fun getLastTrialPopup(ctx: Context): Long =
        prefs(ctx).getLong(KEY_LAST_TRIAL_POPUP, 0L)

    fun setLastTrialPopup(ctx: Context, time: Long) =
        prefs(ctx).edit().putLong(KEY_LAST_TRIAL_POPUP, time).apply()

    fun isTrialAlarmSet(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_TRIAL_ALARM_SET, false)

    fun setTrialAlarmSet(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_TRIAL_ALARM_SET, value).apply()

    // ── Lista de números bloqueados manualmente ────────────────────────────────
    private const val KEY_BLOCKED_NUMBERS = "blocked_numbers"

    /**
     * Normaliza qualquer formato de número para comparação segura:
     *   +5545991234567  →  45991234567
     *   045991234567    →  45991234567
     *   5545991234567   →  45991234567
     *   45991234567     →  45991234567
     * Garante que o bloqueio funcione independente do formato recebido
     * pelo CallScannerService (E.164) ou CallMonitorService (local).
     */
    fun normalizeForBlock(number: String): String {
        val digits = number.filter { it.isDigit() }
        return when {
            digits.startsWith("55") && digits.length >= 12 -> digits.substring(2)
            digits.startsWith("0")  && digits.length >= 11 -> digits.substring(1)
            else -> digits
        }
    }

    fun getBlockedNumbers(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_BLOCKED_NUMBERS, emptySet()) ?: emptySet()

    fun blockNumber(ctx: Context, number: String) {
        val normalized = normalizeForBlock(number)
        if (normalized.isBlank()) return
        val current = getBlockedNumbers(ctx).toMutableSet()
        current.add(normalized)
        prefs(ctx).edit().putStringSet(KEY_BLOCKED_NUMBERS, current).apply()
    }

    fun unblockNumber(ctx: Context, number: String) {
        val normalized = normalizeForBlock(number)
        val current = getBlockedNumbers(ctx).toMutableSet()
        current.remove(normalized)
        prefs(ctx).edit().putStringSet(KEY_BLOCKED_NUMBERS, current).apply()
    }

    /**
     * Normaliza o número antes de comparar — garante que E.164 (+5545...),
     * formato local (045...) e sem prefixo (45...) sejam todos equivalentes.
     */
    fun isNumberBlocked(ctx: Context, number: String): Boolean {
        val normalized = normalizeForBlock(number)
        return normalized.isNotBlank() && normalized in getBlockedNumbers(ctx)
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
