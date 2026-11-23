package com.maguardian.security.service

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.telecom.Call
import android.telecom.CallScreeningService
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.maguardian.security.util.CallOverlayManager
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowService
import org.robolectric.shadows.ShadowSettings
import java.util.concurrent.TimeUnit

@Implements(Call.Details::class)
class CarrierCallDetailsShadow {
    var number: String = "+554141022517"
    var displayName: String = ""
    var direction: Int = Call.Details.DIRECTION_INCOMING

    @Implementation fun getHandle(): Uri = Uri.fromParts("tel", number, null)
    @Implementation fun getCallerDisplayName(): String = displayName
    @Implementation fun getCallDirection(): Int = direction
    @Implementation(minSdk = 30) fun getCallerNumberVerificationStatus(): Int = 0
}

// Robolectric 4.14.1's built-in screening shadow does not extend ShadowService,
// which makes ServiceController.destroy() fail. Keep the normal service cleanup
// and capture only the response that would be delivered to Android Telecom.
@Implements(CallScreeningService::class)
class CarrierScreeningServiceShadow : ShadowService() {
    var respondedDetails: Call.Details? = null
    var response: CallScreeningService.CallResponse? = null

    @Implementation fun respondToCall(
        details: Call.Details,
        callResponse: CallScreeningService.CallResponse
    ) {
        respondedDetails = details
        response = callResponse
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class,
    shadows = [CarrierCallDetailsShadow::class, CarrierScreeningServiceShadow::class])
class CarrierScreeningTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun prepare() {
        CallOverlayManager.dismiss(context)
        shadowOf(Looper.getMainLooper()).idle()
        ShadowSettings.setCanDrawOverlays(true)
        context.getSharedPreferences("maguardian_prefs", 0).edit().clear().commit()
        PrefsHelper.setSubscriptionActive(context, true)
        PrefsHelper.setBlockTelemarketingEnabled(context, true)
        PrefsHelper.setCallAlertsEnabled(context, false)
    }

    @After fun cleanUp() {
        CallOverlayManager.dismiss(context)
        shadowOf(Looper.getMainLooper()).idle()
        ShadowSettings.setCanDrawOverlays(false)
    }

    private fun currentCard(): View? =
        CallOverlayManager::class.java.getDeclaredField("overlayView")
            .apply { isAccessible = true }.get(null) as? View

    private fun descendants(view: View): List<View> =
        listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun screen(
        name: String,
        number: String = "+554141022517",
        direction: Int = Call.Details.DIRECTION_INCOMING
    ): CallScreeningService.CallResponse? {
        val details = Shadow.newInstanceOf(Call.Details::class.java)
        Shadow.extract<CarrierCallDetailsShadow>(details).apply {
            this.number = number
            displayName = name
            this.direction = direction
        }
        val controller = Robolectric.buildService(CallScannerService::class.java).create()
        try {
            val service = controller.get()
            service.onScreenCall(details)
            shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
            val recorded = Shadow.extract<CarrierScreeningServiceShadow>(service)
            if (recorded.response == null) return null
            assertSame(details, recorded.respondedDetails)
            return recorded.response
        } finally { controller.destroy() }
    }

    @Test fun enabledBlockingRejectsAllThreeIdentifiedCarriers() {
        for (name in listOf("TIM", "Vivo", "Claro", "TIM / Black Friday")) {
            val response = requireNotNull(screen(name))
            assertTrue(name, response.disallowCall)
            assertTrue(name, response.silenceCall)
        }
    }

    @Test fun turningOffTheExistingSwitchAllowsCarrierNames() {
        PrefsHelper.setBlockTelemarketingEnabled(context, false)
        for (name in listOf("TIM", "Vivo", "Claro")) {
            assertFalse(name, requireNotNull(screen(name)).disallowCall)
        }
    }

    @Test fun inactiveUserDoesNotReceivePremiumBlocking() {
        PrefsHelper.setSubscriptionActive(context, false)
        assertFalse(PrefsHelper.hasFullAccess(context))
        assertFalse(requireNotNull(screen("TIM")).disallowCall)
    }

    @Test fun activeTrialKeepsTheExistingAccessRule() {
        PrefsHelper.setSubscriptionActive(context, false)
        PrefsHelper.initTrial(context)
        assertTrue(requireNotNull(screen("Vivo")).disallowCall)
    }

    @Test fun unrelatedNamesAndMissingIdentificationAreNotBlockedByThisRule() {
        for (name in listOf("Timóteo", "Declaro", "VIVONET", "")) {
            assertFalse(name, requireNotNull(screen(name)).disallowCall)
        }
    }

    @Test fun outgoingCallsAreNotBlocked() {
        assertNull(screen("TIM", direction = Call.Details.DIRECTION_OUTGOING))
    }

    @Test fun manualBlockingStillWorksWithTheSwitchOff() {
        PrefsHelper.setBlockTelemarketingEnabled(context, false)
        PrefsHelper.blockNumber(context, "+554141022517")
        assertTrue(requireNotNull(screen("Outro nome")).disallowCall)
    }

    @Test fun existingHighRiskBlockingIsPreservedWithTheSwitchOff() {
        PrefsHelper.setBlockTelemarketingEnabled(context, false)
        assertTrue(requireNotNull(screen("", number = "+554141022516")).disallowCall)
    }

    @Test fun automaticallyBlockedCallsDoNotOfferBlockingAgain() {
        PrefsHelper.setCallAlertsEnabled(context, true)
        for (name in listOf("TIM", "Vivo", "Claro")) {
            assertTrue(requireNotNull(screen(name)).disallowCall)
            assertNull(currentCard())
        }
        assertTrue(requireNotNull(screen("", number = "+554141022516")).disallowCall)
        assertNull(currentCard())
    }

    @Test fun manuallyBlockedCallsDoNotShowTheCard() {
        PrefsHelper.setCallAlertsEnabled(context, true)
        PrefsHelper.blockNumber(context, "+554141022517")
        assertTrue(requireNotNull(screen("Outro nome")).disallowCall)
        assertNull(currentCard())
    }

    @Test fun unblockedCallsKeepTheBlockButtonAndBlockedCallsRemoveTheOldCard() {
        PrefsHelper.setCallAlertsEnabled(context, true)
        assertFalse(requireNotNull(screen("Outro nome")).disallowCall)
        val card = requireNotNull(currentCard())
        assertTrue(descendants(card).filterIsInstance<Button>().any { it.text == "Bloquear número" })
        assertTrue(requireNotNull(screen("TIM")).disallowCall)
        assertNull(currentCard())
    }

    @Test fun disabledAlertsDoNotShowACardForUnblockedCalls() {
        assertFalse(requireNotNull(screen("Outro nome")).disallowCall)
        assertNull(currentCard())
    }

    @Test fun reportedCarrierIsBlockedWithoutNameInEverySupportedNumberFormat() {
        PrefsHelper.setCallAlertsEnabled(context, true)
        for (number in listOf(
            "01142145253", "1142145253", "+551142145253",
            "551142145253", "(11) 4214-5253", "+55 (11) 4214-5253"
        )) {
            val response = requireNotNull(screen("", number))
            assertTrue(number, response.disallowCall)
            assertTrue(number, response.silenceCall)
            assertFalse("Preservar histórico de chamadas", response.skipCallLog)
            assertNull("Não oferecer bloquear uma chamada já bloqueada", currentCard())
        }
    }

    @Test fun reportedCarrierAloneDoesNotBlockWithTelemarketingDisabled() {
        PrefsHelper.setBlockTelemarketingEnabled(context, false)
        val response = requireNotNull(screen("", "+551142145253"))
        assertFalse(response.disallowCall)
        assertFalse(response.silenceCall)
    }

    @Test fun reportedCarrierKeepsSubscriptionAndTrialRules() {
        PrefsHelper.setSubscriptionActive(context, false)
        assertFalse(requireNotNull(screen("", "+551142145253")).disallowCall)
        PrefsHelper.initTrial(context)
        assertTrue(requireNotNull(screen("", "+551142145253")).disallowCall)
    }

    @Test fun reportedCarrierDoesNotBlockNeighboringNumbersWithoutOtherEvidence() {
        for (number in listOf("01142145252", "+551142145254")) {
            assertFalse(number, requireNotNull(screen("", number)).disallowCall)
        }
    }

    @Test fun reportedCarrierDoesNotBlockOutgoingCalls() {
        assertNull(screen("", "+551142145253", Call.Details.DIRECTION_OUTGOING))
    }
}
