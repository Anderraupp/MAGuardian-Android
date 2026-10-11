package com.maguardian.security.data

import android.content.Context
import com.maguardian.security.util.PrefsHelper
import org.junit.Assert.*
import org.junit.Test

class TrustedAppsTest {
    @Test
    fun ownerExceptionsAreExcludedFromAllDetectors() {
        for (pkg in listOf("io.metamask", "br.com.serasaexperian.consumidor", "br.com.livelo.app")) {
            assertTrue(pkg, MalwareDatabase.isTrustedApp(pkg))
            assertNull(MalwareDatabase.isMalware(pkg))
            assertNull(MalwareDatabase.checkHeuristic(pkg, "Rapid Cleaner", arrayOf(
                "android.permission.SYSTEM_ALERT_WINDOW",
                "android.permission.RECEIVE_BOOT_COMPLETED"
            )))
            assertNull(MalwareDatabase.checkAdwarePermissions(pkg, "App", arrayOf(
                "android.permission.SYSTEM_ALERT_WINDOW",
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE"
            ), false))
            assertNull(MalwareDatabase.checkHiddenApp(pkg, "App", arrayOf(
                "android.permission.SYSTEM_ALERT_WINDOW",
                "android.permission.RECEIVE_BOOT_COMPLETED"
            ), false, false))
        }
        assertTrue(MalwareDatabase.isScanExempt("io.metamask"))
        assertTrue(MalwareDatabase.isScanExempt("com.binance"))
        assertFalse(MalwareDatabase.isScanExempt("io.metamaskfake"))
        assertFalse(MalwareDatabase.isTrustedApp("io.metamask.fake"))
        assertFalse(MalwareDatabase.isTrustedApp("com.itaufake"))
        assertTrue(MalwareDatabase.isTrustedApp("com.nomadfintech"))
        assertTrue(MalwareDatabase.isTrustedApp("com.nomadfintech.app"))
        assertNotNull(MalwareDatabase.checkAdwarePermissions(
            "com.unknown.adware", "App", arrayOf(
                "android.permission.SYSTEM_ALERT_WINDOW",
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE"
            ), false
        ))
        assertNotNull(MalwareDatabase.isMalware("com.gbwhatsapp"))
    }

    @Test
    fun obsoleteAlertsAreDismissedWithoutRemovalOrLossOfHistory() {
        val ctx = CatalogTestEnvironment.target()
        val p = ctx.getSharedPreferences("maguardian_prefs", Context.MODE_PRIVATE)
        val original = p.getString("threats", "[]")
        val removed = p.getInt("total_threats_removed", 0)
        val found = p.getInt("total_threats_found", 0)
        val kept = p.getStringSet("kept_threat_packages", emptySet())?.toSet()
        p.edit().putString("threats", """[
            {"packageName":"io.metamask","status":"detected"},
            {"packageName":"br.com.serasaexperian.consumidor","status":"detected"},
            {"packageName":"br.com.livelo.app","status":"detected"},
            {"packageName":"com.rapid.filemanger","status":"detected"},
            {"packageName":"io.metamask","status":"removed","removedAt":123},
            {"packageName":"br.com.livelo.app","status":"kept"}
        ]""").commit()
        try {
            val threats = PrefsHelper.getThreats(ctx)
            assertEquals(6, threats.length())
            for (i in 0..2) assertEquals("dismissed", threats.getJSONObject(i).getString("status"))
            assertEquals("detected", threats.getJSONObject(3).getString("status"))
            assertEquals("removed", threats.getJSONObject(4).getString("status"))
            assertEquals(123L, threats.getJSONObject(4).getLong("removedAt"))
            assertEquals("kept", threats.getJSONObject(5).getString("status"))
            assertEquals(removed, p.getInt("total_threats_removed", 0))
            assertEquals(found, p.getInt("total_threats_found", 0))
            assertEquals(kept, p.getStringSet("kept_threat_packages", emptySet()))
            PrefsHelper.saveThreat(ctx, MalwareDatabase.MalwareEntry(
                "io.metamask", "MetaMask", "popup", "high", "old false positive"
            ))
            assertEquals(6, PrefsHelper.getThreats(ctx).length())
        } finally {
            p.edit().putString("threats", original).commit()
        }
    }
}
