package com.maguardian.security.util

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.maguardian.security.receiver.BlockCallReceiver
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSettings

// Never report fixture phone numbers to the real community server.
@Implements(value = CommunityBlocksApi::class, isInAndroidSdk = false)
class OverlayCommunityBlocksShadow {
    @Implementation fun reportBlock(number: String) { reports.add(number) }
    companion object { val reports = mutableListOf<String>() }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class,
    shadows = [OverlayCommunityBlocksShadow::class],
    instrumentedPackages = ["com.maguardian.security.util"])
class CallOverlayBlockTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val number = "+5511987654321"
    private val secondNumber = "+5521987654321"
    private val unrecognized = PhoneAnalyzer.Result(0, "Ligação Segura", "✅", emptyList())

    @Before fun prepare() {
        CallOverlayManager.dismiss(context)
        shadowOf(Looper.getMainLooper()).idle()
        context.getSharedPreferences("maguardian_prefs", 0).edit().clear().commit()
        PrefsHelper.setSubscriptionActive(context, true)
        OverlayCommunityBlocksShadow.reports.clear()
        ShadowSettings.setCanDrawOverlays(true)
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

    private fun show(rawNumber: String = number): View {
        CallOverlayManager.show(context, rawNumber, unrecognized)
        shadowOf(Looper.getMainLooper()).idle()
        return requireNotNull(currentCard()) { "O cartão não pode desaparecer logo após abrir." }
    }

    private fun blockButton(card: View): Button =
        descendants(card).filterIsInstance<Button>().single { it.text == "Bloquear número" }

    private fun clickAndDeliver(card: View) {
        blockButton(card).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val intent = shadowOf(context as Application).broadcastIntents
            .last { it.action == BlockCallReceiver.ACTION_BLOCK_NUMBER }
        assertEquals(BlockCallReceiver::class.java.name, intent.component?.className)
        // Explicitly deliver the captured intent if the manifest receiver has not
        // already been delivered by the simulated Android environment.
        if (!PrefsHelper.isNumberBlocked(context, intent.getStringExtra(BlockCallReceiver.EXTRA_NUMBER)!!)) {
            BlockCallReceiver().onReceive(context, intent)
        }
    }

    @Test fun unrecognizedNumberCanBeBlockedAndReportedOnce() {
        val card = show()
        assertTrue(blockButton(card).isEnabled)
        clickAndDeliver(card)
        assertTrue(PrefsHelper.isNumberBlocked(context, "011987654321"))
        assertTrue(PrefsHelper.isNumberBlocked(context, "11987654321"))
        assertEquals(listOf(number), OverlayCommunityBlocksShadow.reports)
        assertNull(currentCard())
    }

    @Test fun inactiveUserCannotBlockOrReportTheNumber() {
        PrefsHelper.setSubscriptionActive(context, false)
        assertFalse(PrefsHelper.hasFullAccess(context))
        val card = show()
        clickAndDeliver(card)
        assertFalse(PrefsHelper.isNumberBlocked(context, number))
        assertTrue(OverlayCommunityBlocksShadow.reports.isEmpty())
        assertSame(card, currentCard())
    }

    @Test fun activeFreeTrialKeepsTheExistingAccessRule() {
        PrefsHelper.setSubscriptionActive(context, false)
        PrefsHelper.initTrial(context)
        clickAndDeliver(show())
        assertTrue(PrefsHelper.isNumberBlocked(context, number))
        assertEquals(listOf(number), OverlayCommunityBlocksShadow.reports)
    }

    @Test fun hiddenNumbersDoNotHaveABlockButton() {
        assertTrue(descendants(show("")).filterIsInstance<Button>().isEmpty())
    }

    @Test fun alreadyBlockedNumbersAreNotReportedAgain() {
        PrefsHelper.blockNumber(context, number)
        assertTrue(descendants(show()).filterIsInstance<Button>().isEmpty())
        assertTrue(OverlayCommunityBlocksShadow.reports.isEmpty())
    }

    @Test fun replacementCardStaysVisibleAndBlocksTheNewNumberOnly() {
        val first = show()
        val second = show(secondNumber)
        assertNotSame(first, second)
        assertSame(second, currentCard())
        clickAndDeliver(second)
        assertFalse(PrefsHelper.isNumberBlocked(context, number))
        assertTrue(PrefsHelper.isNumberBlocked(context, secondNumber))
        assertEquals(listOf(secondNumber), OverlayCommunityBlocksShadow.reports)
    }

    @Test fun noOverlayIsCreatedWithoutPermission() {
        ShadowSettings.setCanDrawOverlays(false)
        CallOverlayManager.show(context, number, unrecognized)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(currentCard())
    }
}
