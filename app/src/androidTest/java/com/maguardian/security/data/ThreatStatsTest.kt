package com.maguardian.security.data

import android.content.Context
import com.maguardian.security.util.PrefsHelper
import org.junit.Assert.*
import org.junit.Test

/** Preference checks run only in the isolated catalog-validation application. */
class ThreatStatsTest {
    private fun withThreats(block: (Context) -> Unit) {
        val ctx = CatalogTestEnvironment.target()
        val prefs = ctx.getSharedPreferences("maguardian_prefs", Context.MODE_PRIVATE)
        val originalThreats = prefs.getString("threats", "[]")
        val originalFound = prefs.getInt("total_threats_found", 0)
        val originalRemoved = prefs.getInt("total_threats_removed", 0)
        val originalRemovalTime = prefs.getLong("last_threat_removal", 0L)
        prefs.edit()
            .putString("threats", """[
                {"packageName":"com.example.active","status":"detected"},
                {"packageName":"com.example.other","status":"detected"},
                {"packageName":"com.example.old","status":"removed","removedAt":123},
                {"packageName":"com.example.kept","status":"kept"}
            ]""")
            .putInt("total_threats_found", 4)
            .putInt("total_threats_removed", 1)
            .putLong("last_threat_removal", System.currentTimeMillis())
            .commit()
        try {
            block(ctx)
        } finally {
            prefs.edit()
                .putString("threats", originalThreats)
                .putInt("total_threats_found", originalFound)
                .putInt("total_threats_removed", originalRemoved)
                .putLong("last_threat_removal", originalRemovalTime)
                .commit()
        }
    }

    @Test fun removalPreservesOtherThreatsAndHistory() = withThreats { ctx ->
        PrefsHelper.markThreatRemoved(ctx, "com.example.active")
        val threats = PrefsHelper.getThreats(ctx)
        assertEquals(4, threats.length())
        assertEquals("removed", threats.getJSONObject(0).getString("status"))
        assertTrue(threats.getJSONObject(0).getLong("removedAt") > 0)
        assertEquals("detected", threats.getJSONObject(1).getString("status"))
        assertEquals(123L, threats.getJSONObject(2).getLong("removedAt"))
        assertEquals("kept", threats.getJSONObject(3).getString("status"))
        assertEquals(2, PrefsHelper.getTotalThreatsRemoved(ctx))
        assertEquals(4, PrefsHelper.getTotalThreatsFound(ctx))
    }

    @Test fun repeatedOrUnrelatedRemovalDoesNotInflateStatistics() = withThreats { ctx ->
        PrefsHelper.markThreatRemoved(ctx, "com.example.active")
        PrefsHelper.markThreatRemoved(ctx, "com.example.active")
        PrefsHelper.markThreatRemoved(ctx, "com.example.old")
        PrefsHelper.markThreatRemoved(ctx, "com.example.kept")
        PrefsHelper.markThreatRemoved(ctx, "com.example.unknown")
        assertEquals(2, PrefsHelper.getTotalThreatsRemoved(ctx))
        assertEquals("kept", PrefsHelper.getThreats(ctx).getJSONObject(3).getString("status"))
    }

    @Test fun tenMinuteExpiryResetsOnlyRemovedCounter() = withThreats { ctx ->
        val prefs = ctx.getSharedPreferences("maguardian_prefs", Context.MODE_PRIVATE)
        val originalHistory = prefs.getString("threats", "[]")
        prefs.edit().putLong("last_threat_removal",
            System.currentTimeMillis() - 10 * 60 * 1000L).commit()
        assertEquals(0, PrefsHelper.getTotalThreatsRemoved(ctx))
        assertEquals(0L, PrefsHelper.getRemovedCountResetDelay(ctx))
        assertEquals(originalHistory, prefs.getString("threats", "[]"))
        assertEquals(4, PrefsHelper.getTotalThreatsFound(ctx))
        PrefsHelper.markThreatRemoved(ctx, "com.example.active")
        assertEquals(1, PrefsHelper.getTotalThreatsRemoved(ctx))
        assertTrue(PrefsHelper.getRemovedCountResetDelay(ctx) > 0L)
    }
}
