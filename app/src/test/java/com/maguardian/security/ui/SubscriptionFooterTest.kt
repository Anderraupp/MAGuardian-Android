package com.maguardian.security.ui

import android.app.Application
import android.graphics.Paint
import android.view.View
import android.widget.TextView
import com.maguardian.security.R
import com.maguardian.security.billing.PlayOfferPresentation
import com.maguardian.security.util.PrefsHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubscriptionFooterTest {
    @Test fun footerUsesTheConfirmedPriceAndNeverInventsAFreeTrial() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("maguardian_prefs", 0).edit().clear().commit()
        for (palette in listOf("original", "petroleo", "gold")) {
            ctx.getSharedPreferences("guardian_appearance", 0).edit().putString("palette", palette).commit()
            val controller = Robolectric.buildActivity(SubscriptionActivity::class.java).create().start()
            try {
                val activity = controller.get()
                SubscriptionActivity::class.java.getDeclaredMethod("updatePrice").apply { isAccessible = true }.invoke(activity)
                val price = activity.findViewById<TextView>(R.id.tvSubscriptionFooterPrice)
                val trialLabel = activity.findViewById<TextView>(R.id.tvPriceReference)
                assertEquals("Teste 7 dias grátis", trialLabel.text.toString())
                activity.updateFooter(null, false)
                assertEquals(View.GONE, trialLabel.visibility)
                assertEquals("R$ 9,99 por mês.", price.text.toString())
                assertEquals("A oferta e a contratação serão confirmadas pela Google Play.",
                    activity.findViewById<TextView>(R.id.tvLoadingPlans).text.toString())
                val paid = PlayOfferPresentation("R$ 12,34", false, "P1M", "")
                activity.updateFooter(paid, false)
                assertEquals(View.GONE, trialLabel.visibility)
                assertEquals("R$ 12,34 por mês.", price.text.toString())
                val trial = paid.copy(sevenDayTrial = true)
                activity.updateFooter(trial, false)
                assertEquals(View.VISIBLE, trialLabel.visibility)
                assertEquals("Depois, R$ 12,34 por mês.", price.text.toString())
                activity.updateFooter(trial, true)
                assertEquals(View.GONE, trialLabel.visibility)
                assertEquals("R$ 12,34 por mês.", price.text.toString())
                assertFalse("Renderização não pode liberar assinatura", PrefsHelper.isSubscriptionActive(ctx))
                assertEquals("Assinatura pela Google Play",
                    activity.findViewById<TextView>(R.id.tvSubscriptionFooterBilling).text.toString())
                for (id in listOf(R.id.tvPrivacy)) {
                    val link = activity.findViewById<TextView>(id)
                    assertTrue(link.paintFlags and Paint.UNDERLINE_TEXT_FLAG != 0)
                    assertTrue(link.isClickable)
                    assertTrue(link.hasOnClickListeners())
                }
                activity.updateFooter(null, false)
                NativeLayoutChecks.capture(activity, "$palette-premium")
                // Explicitly labelled example of the eligible-offer presentation.
                // This does not load an offer, enable purchasing or activate a trial.
                activity.updateFooter(PlayOfferPresentation("R$ 9,99", true, "P1M", ""), false)
                val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
                val density = activity.resources.displayMetrics.density
                NativeLayoutChecks.measure(root, (402 * density).toInt(), (874 * density).toInt())
                assertTrue(activity.findViewById<View>(R.id.tvPrivacy).width <= 120 * density)
                val footer = activity.findViewById<View>(R.id.subscriptionFooter)
                val bitmap = android.graphics.Bitmap.createBitmap(footer.width, footer.height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(GuardianAppearance.color(activity, R.color.guardian_paper))
                footer.draw(canvas)
                val file = java.io.File("/tmp/guardian-redesign/$palette-premium-rodape-demonstracao.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                val card = trialLabel.parent as View
                val cardBitmap = android.graphics.Bitmap.createBitmap(card.width, card.height, android.graphics.Bitmap.Config.ARGB_8888)
                val cardCanvas = android.graphics.Canvas(cardBitmap)
                cardCanvas.drawColor(GuardianAppearance.color(activity, R.color.guardian_paper))
                card.draw(cardCanvas)
                java.io.File("/tmp/guardian-redesign/$palette-premium-plano-demonstracao.png")
                    .outputStream().use { cardBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                cardBitmap.recycle()
            } finally { controller.stop().destroy() }
        }
    }

    @Test fun footerStaysInTheRequestedOrderWithLargeFonts() {
        RuntimeEnvironment.setQualifiers("w320dp-h874dp")
        val ctx = RuntimeEnvironment.getApplication()
        val resources = ctx.resources
        resources.updateConfiguration(android.content.res.Configuration(resources.configuration).apply { fontScale = 2f }, resources.displayMetrics)
        val controller = Robolectric.buildActivity(SubscriptionActivity::class.java).create().start()
        try {
            val activity = controller.get()
            activity.updateFooter(PlayOfferPresentation("R$ 9,99", true, "P1M", ""), false)
            val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
            NativeLayoutChecks.measure(root, (320 * resources.displayMetrics.density).toInt(), (874 * resources.displayMetrics.density).toInt())
            NativeLayoutChecks.visibleTextFits(root)
            val ids = listOf(R.id.btnSubscribe, R.id.tvSubscriptionFooterPrice,
                R.id.tvSubscriptionFooterBilling, R.id.subscriptionLegalLinks)
            val bounds = ids.map {
                val view = activity.findViewById<View>(it)
                android.graphics.Rect().also { rect ->
                    view.getDrawingRect(rect)
                    (root as android.view.ViewGroup).offsetDescendantRectToMyCoords(view, rect)
                }
            }
            bounds.zipWithNext().forEach { (before, after) -> assertTrue(before.bottom <= after.top) }
        } finally { controller.stop().destroy() }
    }
}
