package com.maguardian.security.billing

import android.app.Application
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.maguardian.security.util.PrefsHelper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
class SubscriptionStatusTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val updates = mutableListOf<Boolean>()
    private lateinit var manager: BillingManager

    @Before fun prepare() {
        context.getSharedPreferences("maguardian_prefs", 0).edit().clear()
            .putLong("trial_start_date", System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8))
            .commit()
        manager = BillingManager(context) { updates.add(it) }
    }

    @After fun dispose() { manager.destroy() }

    private fun result(code: Int) = BillingResult.newBuilder().setResponseCode(code).build()

    // Play's purchase JSON uses 4 for PENDING and 0 for PURCHASED.
    private fun purchase(pending: Boolean) = Purchase(
        """{"productId":"${BillingManager.SKU_MONTHLY}","purchaseToken":"fixture","purchaseState":${if (pending) 4 else 0}}""",
        ""
    )

    @Test fun temporaryErrorsPreserveActiveAccessWithoutReportingAConfirmedStatus() {
        for (code in listOf(
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.NETWORK_ERROR,
            BillingClient.BillingResponseCode.ERROR
        )) {
            PrefsHelper.setSubscriptionActive(context, true)
            assertTrue(manager.applyStatusQueryResult(result(code), emptyList()))
            assertTrue(PrefsHelper.hasFullAccess(context))
            assertTrue(updates.isEmpty())
        }
    }

    @Test fun temporaryErrorDoesNotGrantAccessToAnInactiveUser() {
        PrefsHelper.setSubscriptionActive(context, false)
        assertFalse(manager.applyStatusQueryResult(
            result(BillingClient.BillingResponseCode.NETWORK_ERROR), listOf(purchase(false))
        ))
        assertFalse(PrefsHelper.hasFullAccess(context))
        assertTrue(updates.isEmpty())
    }

    @Test fun confirmedAbsenceRevokesTheOldCachedSubscriptionAndNotifiesTheScreen() {
        PrefsHelper.setSubscriptionActive(context, true)
        assertFalse(manager.applyStatusQueryResult(result(BillingClient.BillingResponseCode.OK), emptyList()))
        assertFalse(PrefsHelper.hasFullAccess(context))
        assertEquals(listOf(false), updates)
    }

    @Test fun pendingPurchaseDoesNotUnlockPremium() {
        val pending = purchase(true)
        assertEquals(Purchase.PurchaseState.PENDING, pending.purchaseState)
        assertFalse(manager.applyStatusQueryResult(result(BillingClient.BillingResponseCode.OK), listOf(pending)))
        assertFalse(PrefsHelper.hasFullAccess(context))
        assertEquals(listOf(false), updates)
    }

    @Test fun purchasedSubscriptionUnlocksPremium() {
        val purchased = purchase(false)
        assertEquals(Purchase.PurchaseState.PURCHASED, purchased.purchaseState)
        assertTrue(manager.applyStatusQueryResult(result(BillingClient.BillingResponseCode.OK), listOf(purchased)))
        assertTrue(PrefsHelper.hasFullAccess(context))
        assertEquals(listOf(true), updates)
    }

    @Test fun confirmedAbsenceOfSubscriptionDoesNotRemoveTheIndependentFreeTrial() {
        context.getSharedPreferences("maguardian_prefs", 0).edit()
            .putLong("trial_start_date", System.currentTimeMillis()).commit()
        assertFalse(manager.applyStatusQueryResult(result(BillingClient.BillingResponseCode.OK), emptyList()))
        assertTrue(PrefsHelper.isTrialActive(context))
        assertTrue(PrefsHelper.hasFullAccess(context))
    }

    @Test fun successfulRecheckAfterAnErrorReplacesThePreviouslySavedStatus() {
        PrefsHelper.setSubscriptionActive(context, true)
        manager.applyStatusQueryResult(result(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE), emptyList())
        assertTrue(PrefsHelper.isSubscriptionActive(context))
        assertTrue(updates.isEmpty())
        manager.applyStatusQueryResult(result(BillingClient.BillingResponseCode.OK), emptyList())
        assertFalse(PrefsHelper.isSubscriptionActive(context))
        assertEquals(listOf(false), updates)
    }
}
