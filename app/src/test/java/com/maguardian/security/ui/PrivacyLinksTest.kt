package com.maguardian.security.ui

import android.app.Application
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.maguardian.security.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
class PrivacyLinksTest {
    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun privacyOpensFromAllEntryPointsWithoutOfferingTerms() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("maguardian_prefs", 0).edit().clear().commit()
        for (palette in listOf("original", "petroleo", "gold")) {
            context.getSharedPreferences("guardian_appearance", 0).edit().putString("palette", palette).commit()
            val welcome = Robolectric.buildActivity(WelcomeActivity::class.java).create().start()
            val premium = Robolectric.buildActivity(SubscriptionActivity::class.java).create().start()
            val main = Robolectric.buildActivity(MainActivity::class.java).create().start()
            try {
                for ((activity, id) in listOf(
                    welcome.get() to R.id.btnWelcomePrivacy,
                    premium.get() to R.id.tvPrivacy
                )) {
                    assertFalse(texts(activity.findViewById(android.R.id.content)).any { it.contains("Termos de uso", true) })
                    assertTrue(activity.findViewById<View>(id).performClick())
                    val intent = shadowOf(activity).nextStartedActivity
                    assertEquals(Intent.ACTION_VIEW, intent.action)
                    assertEquals(GuardianExternal.PRIVACY, intent.dataString)
                }
                main.get().findViewById<View>(R.id.btnToolsLegal).performClick()
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                val list = dialog.listView
                assertEquals(listOf("Política de privacidade", "Informações legais",
                    "Solicitar exclusão de conta e dados", "Detalhes e avisos do aplicativo"),
                    (0 until list.adapter.count).map { list.adapter.getItem(it).toString() })
                list.performItemClick(list.adapter.getView(0, null, list), 0, list.adapter.getItemId(0))
                assertEquals(GuardianExternal.PRIVACY, shadowOf(main.get()).nextStartedActivity.dataString)
            } finally {
                main.stop().destroy()
                premium.stop().destroy()
                welcome.stop().destroy()
            }
        }
    }
}
