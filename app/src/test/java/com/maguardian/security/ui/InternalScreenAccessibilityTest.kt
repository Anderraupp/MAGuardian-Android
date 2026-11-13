package com.maguardian.security.ui

import android.app.Application
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import com.maguardian.security.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InternalScreenAccessibilityTest {
    @Test fun internalLayoutsRemainReadableWithLargeFontsOnSmallScreens() {
        RuntimeEnvironment.setQualifiers("w320dp-h740dp")
        val ctx = RuntimeEnvironment.getApplication()
        for (scale in listOf(1.5f, 2f)) {
            ctx.resources.updateConfiguration(Configuration(ctx.resources.configuration).apply { fontScale = scale }, ctx.resources.displayMetrics)
            for (palette in listOf("original", "petroleo", "gold")) {
                ctx.getSharedPreferences("guardian_appearance", 0).edit().putString("palette", palette).commit()
                val controller = Robolectric.buildActivity(GuardianStartupTest.PaletteActivity::class.java).create().start()
                try {
                    val activity = controller.get()
                    for (layout in listOf(R.layout.activity_link_checker, R.layout.activity_app_cache, R.layout.activity_subscription)) {
                        activity.setContentView(layout)
                        GuardianNavigation.bind(activity)
                        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                        NativeLayoutChecks.measure(root, (320 * activity.resources.displayMetrics.density).toInt(),
                            (740 * activity.resources.displayMetrics.density).toInt())
                        NativeLayoutChecks.visibleTextFits(root)
                    }
                    activity.setContentView(R.layout.activity_welcome)
                    val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                    NativeLayoutChecks.measure(root, (320 * activity.resources.displayMetrics.density).toInt(),
                        (740 * activity.resources.displayMetrics.density).toInt())
                    NativeLayoutChecks.visibleTextFits(root)
                } finally { controller.stop().destroy() }
            }
        }
    }
}
