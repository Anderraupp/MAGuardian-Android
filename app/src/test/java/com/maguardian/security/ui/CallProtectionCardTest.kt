package com.maguardian.security.ui

import android.app.Application
import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.view.View
import android.widget.Button
import com.maguardian.security.R
import com.maguardian.security.util.PrefsHelper
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CallProtectionCardTest {
    private var controller: ActivityController<MainActivity>? = null

    @After fun destroyActivity() {
        controller?.let { if (!it.get().isDestroyed) it.destroy() }
    }

    @Test fun titleAndBothActionsRemainInsideAMatchingToolCardInEveryPalette() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        for (palette in listOf("original", "petroleo", "gold")) {
            ctx.getSharedPreferences("guardian_appearance", 0).edit().putString("palette", palette).commit()
            PrefsHelper.setProtectionEnabled(ctx, false)
            val current = Robolectric.buildActivity(MainActivity::class.java).create().start()
            controller = current
            val activity = current.get()
            val call = activity.findViewById<Button>(R.id.btnCallScanner)
            val link = activity.findViewById<Button>(R.id.btnLinkChecker)
            val cache = activity.findViewById<Button>(R.id.btnCleanCache)
            val perApp = activity.findViewById<Button>(R.id.btnAppCache)
            assertSame("Título não deve ficar em uma legenda externa", link.parent, call.parent)
            assertTrue(call.hasOnClickListeners())
            assertNotNull(call.compoundDrawables[1])
            for (active in listOf(false, true)) {
                if (Build.VERSION.SDK_INT >= 29) {
                    val role = activity.getSystemService(RoleManager::class.java)
                    if (active) shadowOf(role).addHeldRole(RoleManager.ROLE_CALL_SCREENING)
                    else if (role.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                        shadowOf(role).removeHeldRole(RoleManager.ROLE_CALL_SCREENING)
                    }
                }
                MainActivity::class.java.getDeclaredMethod("updateCallScannerButton")
                    .apply { isAccessible = true }.invoke(activity)
                val action = if (active && Build.VERSION.SDK_INT >= 29) "Desativar" else "Ativar"
                assertEquals("Proteção de ligações\n$action", call.text.toString())
                for (screenDp in listOf(320, 360, 402)) {
                    val width = ((screenDp - 40) * activity.resources.displayMetrics.density).toInt()
                    // AppCompat autosizing on API 26 can request a second layout
                    // after choosing a font size, as ViewRoot would do on-device.
                    repeat(3) {
                        call.forceLayout()
                        for (row in listOf(link.parent as View, cache.parent as View)) {
                            row.forceLayout()
                            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                            row.layout(0, 0, row.measuredWidth, row.measuredHeight)
                        }
                    }
                    call.draw(android.graphics.Canvas())
                    for (other in listOf(link, cache, perApp)) {
                        assertEquals("Largura igual em $palette/$screenDp", other.measuredWidth, call.measuredWidth)
                        assertEquals("Altura igual em $palette/$screenDp", other.measuredHeight, call.measuredHeight)
                    }
                    val textLayout = call.layout
                    assertNotNull(textLayout)
                    assertTrue(call.lineCount <= 4)
                    assertEquals(call.text.length, textLayout.getLineEnd(call.lineCount - 1))
                    assertEquals(0, textLayout.getEllipsisCount(call.lineCount - 1))
                    assertTrue("Texto deve caber dentro do cartão",
                        textLayout.height <= call.height - call.compoundPaddingTop - call.compoundPaddingBottom)
                }
            }
            current.stop().destroy()
        }
    }
}
