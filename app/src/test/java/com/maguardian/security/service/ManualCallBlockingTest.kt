package com.maguardian.security.service

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.telecom.Call
import android.telecom.CallScreeningService
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.maguardian.security.receiver.BlockCallReceiver
import com.maguardian.security.ui.CallAlertActivity
import com.maguardian.security.util.CallOverlayManager
import com.maguardian.security.util.OverlayCommunityBlocksShadow
import com.maguardian.security.util.PrefsHelper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import java.util.concurrent.TimeUnit

// Exercise the real local block handlers and screening service, but never send
// fixture numbers to the community server or depend on that server being live.
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [29, 34], application = Application::class,
    shadows = [
        CarrierCallDetailsShadow::class, CarrierScreeningServiceShadow::class,
        OverlayCommunityBlocksShadow::class
    ],
    instrumentedPackages = ["com.maguardian.security.util"]
)
class ManualCallBlockingTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val number = "+554141022517"

    @Before fun prepare() {
        CallOverlayManager.dismiss(context)
        shadowOf(Looper.getMainLooper()).idle()
        context.getSharedPreferences("maguardian_prefs", 0).edit().clear().commit()
        PrefsHelper.setSubscriptionActive(context, true)
        PrefsHelper.setBlockTelemarketingEnabled(context, false)
        PrefsHelper.setCallAlertsEnabled(context, false)
        OverlayCommunityBlocksShadow.reports.clear()
        ShadowSettings.setCanDrawOverlays(true)
    }

    @After fun cleanUp() {
        CallOverlayManager.dismiss(context)
        shadowOf(Looper.getMainLooper()).idle()
        ShadowSettings.setCanDrawOverlays(false)
    }

    private fun card(): View? = CallOverlayManager::class.java
        .getDeclaredField("overlayView").apply { isAccessible = true }.get(null) as? View

    private fun descendants(view: View): List<View> =
        listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else emptyList()

    private fun screen(rawNumber: String = number): CallScreeningService.CallResponse {
        val details = Shadow.newInstanceOf(Call.Details::class.java)
        Shadow.extract<CarrierCallDetailsShadow>(details).apply {
            this.number = rawNumber
            displayName = ""
            direction = Call.Details.DIRECTION_INCOMING
        }
        // A fresh service for every call checks that blocking isn't confined to
        // a flag in the service instance that displayed the original warning.
        val controller = Robolectric.buildService(CallScannerService::class.java).create()
        try {
            controller.get().onScreenCall(details)
            shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
            return requireNotNull(
                Shadow.extract<CarrierScreeningServiceShadow>(controller.get()).response
            )
        } finally {
            controller.destroy()
        }
    }

    private fun blockFromNotification() {
        BlockCallReceiver().onReceive(context,
            Intent(context, BlockCallReceiver::class.java)
                .setAction(BlockCallReceiver.ACTION_BLOCK_NUMBER)
                .putExtra(BlockCallReceiver.EXTRA_NUMBER, number))
    }

    @Test fun notificationActionSavesAndBlocksFutureCallsWithoutTelemarketingOrAlerts() {
        assertFalse(screen().disallowCall)
        blockFromNotification()
        assertEquals(setOf("4141022517"), PrefsHelper.getBlockedNumbers(context))
        for (format in listOf(
            "04141022517", "4141022517", "+554141022517",
            "554141022517", "(41) 4102-2517", "+55 (41) 4102-2517"
        )) {
            val response = screen(format)
            assertTrue(format, response.disallowCall)
            assertTrue(format, response.silenceCall)
            assertFalse(response.skipCallLog)
        }
        assertEquals(listOf(number), OverlayCommunityBlocksShadow.reports)
        assertTrue(PrefsHelper.getCommunityBlocks(context).isEmpty())
    }

    @Test fun floatingCardActionBlocksTheNextCallAndDoesNotOfferBlockingAgain() {
        PrefsHelper.setCallAlertsEnabled(context, true)
        assertFalse(screen().disallowCall)
        val firstCard = requireNotNull(card())
        descendants(firstCard).filterIsInstance<Button>()
            .single { it.text == "Bloquear número" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val intent = shadowOf(context as Application).broadcastIntents
            .last { it.action == BlockCallReceiver.ACTION_BLOCK_NUMBER }
        if (!PrefsHelper.isNumberBlocked(context, number)) {
            BlockCallReceiver().onReceive(context, intent)
        }
        assertTrue(PrefsHelper.isNumberBlocked(context, number))
        assertTrue(screen("04141022517").disallowCall)
        assertNull(card())
        assertEquals(listOf(number), OverlayCommunityBlocksShadow.reports)
    }

    @Test fun fullScreenAlertButtonBlocksTheNextCall() {
        val intent = Intent(context, CallAlertActivity::class.java)
            .putExtra(CallAlertActivity.EXTRA_NUMBER, number)
        val controller = Robolectric.buildActivity(CallAlertActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            descendants(activity.window.decorView).filterIsInstance<Button>()
                .single { it.text.toString().contains("Bloquear") }.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(activity.isFinishing)
            assertTrue(screen().disallowCall)
            assertEquals(listOf(number), OverlayCommunityBlocksShadow.reports)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun unblockingRestoresCallsWithoutChangingTheNeighboringNumber() {
        blockFromNotification()
        assertTrue(screen().disallowCall)
        assertFalse(screen("+554141022518").disallowCall)
        PrefsHelper.unblockNumber(context, "04141022517")
        assertFalse(screen().disallowCall)
    }

    @Test fun savedManualBlockKeepsTheExistingSubscriptionRequirement() {
        blockFromNotification()
        PrefsHelper.setSubscriptionActive(context, false)
        assertFalse(PrefsHelper.hasFullAccess(context))
        assertTrue(PrefsHelper.isNumberBlocked(context, number))
        assertFalse(screen().disallowCall)
        PrefsHelper.setSubscriptionActive(context, true)
        assertTrue(screen().disallowCall)
    }
}
