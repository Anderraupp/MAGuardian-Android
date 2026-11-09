package com.maguardian.security.ui

import android.app.Application
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.maguardian.security.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
class GuardianStartupTest {
    class PaletteActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            GuardianAppearance.apply(this)
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_main)
            GuardianNavigation.bind(this)
        }
    }

    private fun inflate(palette: String) {
        RuntimeEnvironment.getApplication().getSharedPreferences("guardian_appearance", 0)
            .edit().putString("palette", palette).commit()
        val controller = Robolectric.buildActivity(PaletteActivity::class.java).setup()
        val activity = controller.get()
        for (layout in listOf(
            R.layout.activity_main, R.layout.activity_link_checker,
            R.layout.activity_app_cache, R.layout.activity_subscription
        )) {
            activity.setContentView(layout)
            GuardianNavigation.bind(activity)
            assertEquals(5, activity.findViewById<BottomNavigationView>(R.id.guardianBottomNav).menu.size())
            GuardianAppearance.color(activity, R.color.guardian_ink)
        }
        controller.pause().stop().destroy()
    }

    @Test fun opensOriginalPalette() = inflate("original")
    @Test fun opensPetroleoPalette() = inflate("petroleo")
    @Test fun opensGoldPalette() = inflate("gold")

    @Test fun opensActualMainActivity() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals(5, controller.get().findViewById<BottomNavigationView>(R.id.guardianBottomNav).menu.size())
        controller.pause().stop().destroy()
    }
}
