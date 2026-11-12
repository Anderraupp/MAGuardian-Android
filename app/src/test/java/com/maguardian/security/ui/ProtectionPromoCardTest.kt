package com.maguardian.security.ui

import android.app.Application
import android.app.AppOpsManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.maguardian.security.R
import com.maguardian.security.util.PrefsHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProtectionPromoCardTest {
    @Test fun premiumCardFitsAllPalettesAndExpiredTrialOpensExistingSubscription() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("maguardian_prefs", 0).edit().clear()
            .putLong("trial_start_date", System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8)).commit()
        for (palette in listOf("original", "petroleo", "gold")) {
            ctx.getSharedPreferences("guardian_appearance", 0).edit().putString("palette", palette).commit()
            val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
            val activity = controller.get()
            try {
                MainActivity::class.java.getDeclaredMethod("refreshUI").apply { isAccessible = true }.invoke(activity)
                val card = activity.findViewById<LinearLayout>(R.id.cardStatus)
                val action = activity.findViewById<TextView>(R.id.btnToggle)
                assertEquals("Seu dispositivo merece proteção.", activity.findViewById<TextView>(R.id.tvStatus).text.toString())
                assertEquals("Disponível", activity.findViewById<TextView>(R.id.tvStatusBadge).text.toString())
                assertEquals("Conhecer planos e ativar proteção", action.text.toString())
                assertEquals(View.GONE, activity.findViewById<View>(R.id.tvProtectionTrial).visibility)
                for (screenDp in listOf(320, 360, 402)) {
                    val width = ((screenDp - 40) * activity.resources.displayMetrics.density).toInt()
                    card.forceLayout()
                    card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    card.layout(0, 0, card.measuredWidth, card.measuredHeight)
                    fun verify(view: View) {
                        if (view is TextView && view.visibility == View.VISIBLE && view.text.isNotEmpty()) {
                            view.draw(Canvas())
                            val layout = view.layout
                            assertEquals("Frase inteira em $palette/$screenDp: ${view.text}",
                                view.text.length, layout.getLineEnd(view.lineCount - 1))
                            assertTrue("Altura suficiente para ${view.text}",
                                layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
                        }
                        if (view is android.view.ViewGroup) {
                            for (i in 0 until view.childCount) verify(view.getChildAt(i))
                        }
                    }
                    verify(card)
                    assertEquals(card.paddingLeft, action.left)
                    assertEquals(card.width - card.paddingRight, action.right)
                    if (palette == "original" && screenDp == 360 && android.os.Build.VERSION.SDK_INT == 34) {
                        val bitmap = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
                        card.draw(Canvas(bitmap))
                        java.io.File(System.getProperty("java.io.tmpdir"), "guardian-protection-card.png")
                            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }
                val protectionBeforeClick = PrefsHelper.isProtectionEnabled(ctx)
                action.performClick()
                assertEquals(SubscriptionActivity::class.java.name,
                    shadowOf(activity).nextStartedActivityForResult.intent.component?.className)
                assertFalse(PrefsHelper.isSubscriptionActive(ctx))
                assertEquals(protectionBeforeClick, PrefsHelper.isProtectionEnabled(ctx))
            } finally {
                if (!activity.isDestroyed) controller.stop().destroy()
            }
        }
    }

    @Test fun trialMessageReflectsRemainingTimeAndDisappearsForSubscribers() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("maguardian_prefs", 0).edit().clear().commit()
        PrefsHelper.initTrial(ctx)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        val activity = controller.get()
        try {
            val refresh = MainActivity::class.java.getDeclaredMethod("refreshUI").apply { isAccessible = true }
            val footer = activity.findViewById<TextView>(R.id.tvProtectionTrial)
            refresh.invoke(activity)
            assertEquals(View.VISIBLE, footer.visibility)
            assertEquals("7 dias grátis para experimentar", footer.text.toString())
            ctx.getSharedPreferences("maguardian_prefs", 0).edit()
                .putLong("trial_start_date", System.currentTimeMillis() - TimeUnit.DAYS.toMillis(3)).commit()
            refresh.invoke(activity)
            assertTrue(footer.text.toString().contains("restantes"))
            PrefsHelper.setSubscriptionActive(ctx, true)
            PrefsHelper.setProtectionEnabled(ctx, true)
            shadowOf(activity.getSystemService(AppOpsManager::class.java)).setMode(
                AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), activity.packageName,
                AppOpsManager.MODE_ALLOWED)
            refresh.invoke(activity)
            assertEquals(View.GONE, footer.visibility)
            assertEquals("Ativa", activity.findViewById<TextView>(R.id.tvStatusBadge).text.toString())
            assertEquals("Desativar", activity.findViewById<TextView>(R.id.btnToggle).text.toString())
        } finally {
            if (!activity.isDestroyed) controller.stop().destroy()
        }
    }
}
