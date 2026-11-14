package com.maguardian.security.util

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
class CommunityBlockPolicyTest {
    @Test fun oneReportQualifiesForTheSharedList() {
        assertEquals(1, AppConfig.COMMUNITY_BLOCK_MIN_REPORTS)
    }

    @Test fun downloadedNumberBlocksOnADeviceThatNeverBlockedItManually() {
        val context = RuntimeEnvironment.getApplication()
        // Synthetic fixture, kept entirely in Robolectric's disposable preferences.
        val number = "12345678"
        PrefsHelper.unblockNumber(context, number)
        PrefsHelper.saveCommunityBlocks(context, emptySet())
        assertFalse(PrefsHelper.isNumberBlocked(context, number))

        PrefsHelper.saveCommunityBlocks(context, setOf(number))

        assertFalse(PrefsHelper.getBlockedNumbers(context).contains(number))
        assertTrue(PrefsHelper.isNumberBlocked(context, number))
    }
}
