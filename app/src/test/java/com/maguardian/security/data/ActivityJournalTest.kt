package com.maguardian.security.data

import android.app.Application
import com.maguardian.security.util.PrefsHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
class ActivityJournalTest {
    @Test fun realDailyEventsSurviveLegacyResetAndDeduplicateRemovals() {
        val ctx = RuntimeEnvironment.getApplication()
        val now = System.currentTimeMillis()
        val before = ActivityJournal.daily(ctx)
        val previousDay = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
        ActivityJournal.record(ctx, "old:scan", "scan", previousDay, source = "manual")
        PrefsHelper.setLastScan(ctx, now)
        // The same completed event is idempotent, but a separate automatic completion is counted.
        PrefsHelper.setLastScan(ctx, now)
        ActivityJournal.record(ctx, "auto:scan", "scan", now + 1, source = "automática")
        val malware = MalwareDatabase.MalwareEntry("test.journal.threat", "Fixture inerte", "adware", "high", "Teste de registro local")
        PrefsHelper.saveThreat(ctx, malware)
        PrefsHelper.markThreatRemoved(ctx, malware.packageName)
        PrefsHelper.markThreatRemoved(ctx, malware.packageName)
        PrefsHelper.markThreatRemoved(ctx, "not.detected.package")
        val after = ActivityJournal.daily(ctx)
        assertEquals(before.scans + 2, after.scans)
        assertEquals(before.found + 1, after.found)
        assertEquals(before.removed + 1, after.removed)
        PrefsHelper.resetStats(ctx)
        assertEquals(after, ActivityJournal.daily(ctx))
        assertTrue(ActivityJournal.page(ctx).any { it.kind == "removed" && it.name == "Fixture inerte" })
    }
    @Test fun historyIsPagedAndDoesNotInferOldScansOrAlterAccessSettings() {
        val ctx = RuntimeEnvironment.getApplication()
        val protection = PrefsHelper.isProtectionEnabled(ctx)
        val subscribed = PrefsHelper.isSubscriptionActive(ctx)
        val now = System.currentTimeMillis()
        repeat(105) { ActivityJournal.record(ctx, "page:$it", "scan", now + it, source = "manual") }
        assertEquals(100, ActivityJournal.page(ctx).size)
        assertTrue(ActivityJournal.page(ctx, 100).size >= 5)
        assertEquals(protection, PrefsHelper.isProtectionEnabled(ctx))
        assertEquals(subscribed, PrefsHelper.isSubscriptionActive(ctx))
    }
}
