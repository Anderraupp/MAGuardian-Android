package com.maguardian.security.data

import com.maguardian.security.util.PrefsHelper
import org.junit.Assert.*
import org.junit.Test

class ThreatCatalogTimingTest {
    @Test fun nextCheckUsesRemainingTimeSinceLastAttempt() {
        val ctx = CatalogTestEnvironment.target()
        val previous = PrefsHelper.getCatalogAttempt(ctx)
        try {
            PrefsHelper.setCatalogAttempt(ctx, System.currentTimeMillis() -
                ThreatCatalogUpdater.UPDATE_INTERVAL_MS + 30_000L)
            val delay = ThreatCatalogUpdater.nextCheckDelay(ctx)
            assertTrue(delay in 1L..30_000L)
        } finally {
            PrefsHelper.setCatalogAttempt(ctx, previous)
        }
    }

    @Test fun overdueAttemptDoesNotCreateABusyRetryLoop() {
        val ctx = CatalogTestEnvironment.target()
        val previous = PrefsHelper.getCatalogAttempt(ctx)
        try {
            PrefsHelper.setCatalogAttempt(ctx, System.currentTimeMillis() -
                ThreatCatalogUpdater.UPDATE_INTERVAL_MS - 1L)
            assertEquals(300_000L, ThreatCatalogUpdater.nextCheckDelay(ctx))
        } finally {
            PrefsHelper.setCatalogAttempt(ctx, previous)
        }
    }
}
