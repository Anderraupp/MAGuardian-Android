package com.maguardian.security.data

import android.content.Context
import com.maguardian.security.util.PrefsHelper
import org.junit.Assert.*
import org.junit.Test

class TrustedAppsTest {
    @Test
    fun legacyPrefixCoverageNeverBecomesNarrower() {
        for (fieldName in listOf(
            "TRUSTED_FINANCIAL_PREFIXES", "TRUSTED_VENDOR_PREFIXES", "SCAN_EXEMPT_PREFIXES"
        )) {
            val field = MalwareDatabase.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            val prefixes = (field.get(MalwareDatabase) as Collection<*>).filterIsInstance<String>()
            for (prefix in prefixes) {
                for (pkg in listOf(prefix, prefix + "activity", prefix + "card.app", prefix + "demo.feature")) {
                    // Estes pacotes passavam no startsWith original: todos devem continuar isentos.
                    val exempt = if (fieldName == "SCAN_EXEMPT_PREFIXES") {
                        MalwareDatabase.isScanExempt(pkg)
                    } else {
                        MalwareDatabase.isTrustedApp(pkg)
                    }
                    assertTrue("$fieldName perdeu cobertura de $pkg", exempt)
                }
            }
        }
    }

    @Test
    fun registeredBanksRemainExcludedAcrossDetectors() {
        val permissions = arrayOf(
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE"
        )
        for (pkg in listOf(
            "com.santander.app", "br.com.santander.mobi", "br.com.santander.way",
            "com.nu.production", "br.com.bb.android", "com.itau", "com.bradesco",
            "br.gov.caixa", "br.com.intermedium", "com.c6bank.app",
            "br.com.sicoob.sicredimobile", "br.com.neon.app", "br.com.sicredi",
            "com.itaucard.activity", "com.bradescosegurosmobile"
        )) {
            assertTrue(pkg, MalwareDatabase.isTrustedApp(pkg))
            assertNull(MalwareDatabase.isMalware(pkg))
            assertNull(MalwareDatabase.checkHeuristic(pkg, "Cleaner", permissions))
            assertNull(MalwareDatabase.checkAdwarePermissions(pkg, "App", permissions, false))
            assertNull(MalwareDatabase.checkHiddenApp(pkg, "App", permissions, false, false))
        }
        // O nome do banco não basta para tornar um pacote desconhecido confiável.
        assertFalse(MalwareDatabase.isTrustedApp("com.santander.appfake"))
        assertFalse(MalwareDatabase.isTrustedApp("com.fake.santander"))
    }

    @Test
    fun ownerExceptionsAreExcludedFromAllDetectors() {
        for (pkg in listOf(
            "io.metamask", "br.com.serasaexperian.consumidor", "br.com.livelo.app",
            "br.com.timbrasil.meutim", "br.com.vivo"
        )) {
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
        assertTrue(MalwareDatabase.isTrustedApp("com.itaucard.activity"))
        assertFalse(MalwareDatabase.isTrustedApp("br.com.timbrasil.meutimfake"))
        assertFalse(MalwareDatabase.isTrustedApp("br.com.vivofake"))
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
            {"packageName":"br.com.livelo.app","status":"kept"},
            {"packageName":"com.santander.app","status":"detected"},
            {"packageName":"br.com.timbrasil.meutim","status":"detected"},
            {"packageName":"br.com.vivo","status":"detected"},
            {"packageName":"com.itaucard.activity","status":"detected"},
            {"packageName":"com.bradescosegurosmobile","status":"detected"}
        ]""").commit()
        try {
            val threats = PrefsHelper.getThreats(ctx)
            assertEquals(11, threats.length())
            for (i in 0..2) assertEquals("dismissed", threats.getJSONObject(i).getString("status"))
            assertEquals("detected", threats.getJSONObject(3).getString("status"))
            assertEquals("removed", threats.getJSONObject(4).getString("status"))
            assertEquals(123L, threats.getJSONObject(4).getLong("removedAt"))
            assertEquals("kept", threats.getJSONObject(5).getString("status"))
            assertEquals("dismissed", threats.getJSONObject(6).getString("status"))
            assertEquals("dismissed", threats.getJSONObject(7).getString("status"))
            assertEquals("dismissed", threats.getJSONObject(8).getString("status"))
            assertEquals("dismissed", threats.getJSONObject(9).getString("status"))
            assertEquals("dismissed", threats.getJSONObject(10).getString("status"))
            assertEquals(removed, p.getInt("total_threats_removed", 0))
            assertEquals(found, p.getInt("total_threats_found", 0))
            assertEquals(kept, p.getStringSet("kept_threat_packages", emptySet()))
            PrefsHelper.saveThreat(ctx, MalwareDatabase.MalwareEntry(
                "io.metamask", "MetaMask", "popup", "high", "old false positive"
            ))
            assertEquals(11, PrefsHelper.getThreats(ctx).length())
        } finally {
            p.edit().putString("threats", original).commit()
        }
    }
}
