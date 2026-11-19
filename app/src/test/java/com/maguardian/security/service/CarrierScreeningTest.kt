package com.maguardian.security.service

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.telecom.Call
import android.telecom.CallScreeningService
import com.maguardian.security.util.PrefsHelper
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
        context.getSharedPreferences("maguardian_prefs", 0).edit().clear().commit()
        PrefsHelper.setSubscriptionActive(context, true)
        PrefsHelper.setBlockTelemarketingEnabled(context, true)
        PrefsHelper.setCallAlertsEnabled(context, false)
    }

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
}
