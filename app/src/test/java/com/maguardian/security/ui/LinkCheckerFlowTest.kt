package com.maguardian.security.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.maguardian.security.R
import com.maguardian.security.util.LinkChecker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
class LinkCheckerFlowTest {
    private fun check(activity: LinkCheckerActivity, url: String) {
        activity.findViewById<EditText>(R.id.etUrl).setText(url)
        activity.findViewById<Button>(R.id.btnCheck).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertVerdict(activity: LinkCheckerActivity, prefix: String, color: Int) {
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.cardResult).visibility)
        val label = activity.findViewById<TextView>(R.id.tvVerdict)
        assertTrue(label.text.toString(), label.text.startsWith(prefix))
        assertEquals(GuardianAppearance.color(activity, color), label.currentTextColor)
    }

    @Test fun safeSuspiciousAndDangerousWorkInEveryPalette() {
        for (palette in listOf("original", "petroleo", "gold")) {
            RuntimeEnvironment.getApplication().getSharedPreferences("guardian_appearance", 0)
                .edit().putString("palette", palette).commit()
            val controller = Robolectric.buildActivity(LinkCheckerActivity::class.java).setup()
            val activity = controller.get()
            for ((url, prefix, color) in listOf(
                Triple("https://gov.br", "Seguro", R.color.guardian_success),
                Triple("https://bit.ly/abc", "Suspeito", R.color.guardian_warning),
                Triple("https://example.org/arquivo.exe", "Alto risco", R.color.guardian_danger),
                Triple("https://seguroitau.com/", "Suspeito", R.color.guardian_warning),
                Triple("https://google.com@evil.example/login", "Alto risco", R.color.guardian_danger)
            )) {
                check(activity, url)
                assertVerdict(activity, prefix, color)
                val expected = LinkChecker.check(url)
                assertEquals("Pontuação de atenção: ${expected.score}/100",
                    activity.findViewById<TextView>(R.id.tvScore).text.toString())
                assertEquals(expected.reasons.size,
                    activity.findViewById<LinearLayout>(R.id.llReasons).childCount)
            }
            controller.pause().stop().destroy()
        }
    }

    @Test fun editingAndInvalidInputNeverKeepPreviousGreenResult() {
        val controller = Robolectric.buildActivity(LinkCheckerActivity::class.java).setup()
        val activity = controller.get()
        for (invalid in listOf("", "https://", "not a link", "javascript:alert(1)",
            "mailto:name@example.org", "ftp://example.org/file", "https://example.org:99999/",
            "https://example..org/", "https://exa mple.org")) {
            check(activity, "https://gov.br")
            assertVerdict(activity, "Seguro", R.color.guardian_success)
            activity.findViewById<EditText>(R.id.etUrl).setText(invalid)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.cardResult).visibility)
            activity.findViewById<Button>(R.id.btnCheck).performClick()
            assertEquals(invalid, View.GONE, activity.findViewById<View>(R.id.cardResult).visibility)
            assertNotNull(invalid, activity.findViewById<EditText>(R.id.etUrl).error)
        }
        check(activity, "gov.br")
        assertVerdict(activity, "Seguro", R.color.guardian_success)
        controller.pause().stop().destroy()
    }

    @Test fun clipboardAndLocalAnalysisWorkInAirplaneMode() {
        val controller = Robolectric.buildActivity(LinkCheckerActivity::class.java).setup()
        val activity = controller.get()
        Settings.Global.putInt(activity.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val url = "https://example.org/arquivo.exe"
        clipboard.setPrimaryClip(ClipData.newPlainText("link", url))
        activity.findViewById<Button>(R.id.btnPaste).performClick()
        assertEquals(url, activity.findViewById<EditText>(R.id.etUrl).text.toString())
        activity.findViewById<Button>(R.id.btnCheck).performClick()
        assertVerdict(activity, "Alto risco", R.color.guardian_danger)
        check(activity, "https://gov.br")
        assertVerdict(activity, "Seguro", R.color.guardian_success)
        controller.pause().stop().destroy()
    }

    @Test fun themeRecreationKeepsInputVerdictAndReasons() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("guardian_appearance", 0)
        prefs.edit().putString("palette", "original").commit()
        val controller = Robolectric.buildActivity(LinkCheckerActivity::class.java).setup()
        val url = "https://itau-validar-conta.xyz/login"
        check(controller.get(), url)
        val score = controller.get().findViewById<TextView>(R.id.tvScore).text.toString()
        prefs.edit().putString("palette", "gold").commit()
        controller.recreate()
        val activity = controller.get()
        assertEquals(url, activity.findViewById<EditText>(R.id.etUrl).text.toString())
        assertVerdict(activity, "Alto risco", R.color.guardian_danger)
        assertEquals(score, activity.findViewById<TextView>(R.id.tvScore).text.toString())
        assertTrue(activity.findViewById<LinearLayout>(R.id.llReasons).childCount > 0)
        activity.findViewById<EditText>(R.id.etUrl).setText("https://gov.br")
        assertEquals(View.GONE, activity.findViewById<View>(R.id.cardResult).visibility)
        controller.pause().stop().destroy()
    }

    @Test fun unknownResultCannotBeShownAsSafe() {
        val controller = Robolectric.buildActivity(LinkCheckerActivity::class.java).setup()
        val activity = controller.get()
        check(activity, "https://gov.br")
        LinkCheckerActivity::class.java.getDeclaredMethod("showResult", LinkChecker.Result::class.java)
            .apply { isAccessible = true }
            .invoke(activity, LinkChecker.Result(0, "UNAVAILABLE", "Falha", emptyList()))
        assertEquals(View.GONE, activity.findViewById<View>(R.id.cardResult).visibility)
        assertTrue(activity.findViewById<EditText>(R.id.etUrl).error.contains("Não foi possível"))
        controller.pause().stop().destroy()
    }

    @Test fun homeOpensLinkCheckerAndBackClosesIt() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        main.get().findViewById<Button>(R.id.btnLinkChecker).performClick()
        assertEquals(LinkCheckerActivity::class.java.name,
            shadowOf(main.get()).nextStartedActivity.component?.className)
        main.pause().stop().destroy()
        val link = Robolectric.buildActivity(LinkCheckerActivity::class.java).setup()
        link.get().findViewById<TextView>(R.id.btnBack).performClick()
        assertTrue(link.get().isFinishing)
        link.pause().stop().destroy()
    }
}
