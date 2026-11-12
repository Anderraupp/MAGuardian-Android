package com.maguardian.security.ui

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.maguardian.security.R
import com.maguardian.security.util.AppNoticeCenter
import com.maguardian.security.util.AppUpdateNotifier
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GuardianNoticesTest {
    private lateinit var ctx: Context
    @Before fun reset() {
        ctx = RuntimeEnvironment.getApplication()
        for (name in listOf("maguardian_prefs", "guardian_notice_center", "ma_app_update_notifications")) {
            ctx.getSharedPreferences(name, 0).edit().clear().commit()
        }
        PrefsHelper.initTrial(ctx)
    }

    @Suppress("DEPRECATION")
    private fun installed() = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode

    private fun playUpdate(version: Int) {
        val play = FakeAppUpdateManager(ctx)
        play.setUpdateAvailable(version)
        AppUpdateNotifier.handle(ctx, play.appUpdateInfo.result)
    }

    @Test fun subscriptionNoticeMatchesActualSubscriptionNotTrial() {
        assertTrue(PrefsHelper.isTrialActive(ctx))
        assertEquals("subscription", AppNoticeCenter.notices(ctx).single().id)
        PrefsHelper.setSubscriptionActive(ctx, true)
        assertTrue(AppNoticeCenter.notices(ctx).isEmpty())
        PrefsHelper.setSubscriptionActive(ctx, false)
        assertEquals(1, AppNoticeCenter.notices(ctx).size)
    }

    @Test fun playNoticeDoesNotDependOnSystemNotificationsAndNewVersionIsUnread() {
        val manager = ctx.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(false)
        playUpdate(installed() + 1)
        assertEquals(2, AppNoticeCenter.notices(ctx).size)
        assertEquals(2, AppNoticeCenter.unreadCount(ctx))
        AppNoticeCenter.markSeen(ctx, AppNoticeCenter.notices(ctx))
        assertEquals(0, AppNoticeCenter.unreadCount(ctx))
        playUpdate(installed() + 1)
        assertEquals(0, AppNoticeCenter.unreadCount(ctx))
        playUpdate(installed() + 2)
        assertEquals(1, AppNoticeCenter.unreadCount(ctx))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun noUpdateOrInstalledVersionNeverShowsFalseUpdate() {
        PrefsHelper.setSubscriptionActive(ctx, true)
        assertTrue(AppNoticeCenter.notices(ctx).isEmpty())
        playUpdate(installed())
        assertTrue(AppNoticeCenter.notices(ctx).isEmpty())
        playUpdate(installed() + 1)
        assertEquals(1, AppNoticeCenter.notices(ctx).size)
        val play = FakeAppUpdateManager(ctx)
        play.setUpdateNotAvailable()
        AppUpdateNotifier.handle(ctx, play.appUpdateInfo.result)
        assertTrue(AppNoticeCenter.notices(ctx).isEmpty())
        ctx.getSharedPreferences("guardian_notice_center", 0).edit().putInt("play_version", installed()).commit()
        assertTrue(AppNoticeCenter.notices(ctx).isEmpty())
    }

    @Test fun bellOpensSubscriptionAndMarksNoticesReadWithoutUnlocking() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        val activity = controller.get()
        try {
            GuardianNotices.refresh(activity)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.tvNotificationBadge).visibility)
            activity.findViewById<View>(R.id.btnNotifications).performClick()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertEquals(1, dialog.listView.adapter.count)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.tvNotificationBadge).visibility)
            dialog.listView.performItemClick(null, 0, 0)
            val target = shadowOf(activity).nextStartedActivityForResult
            assertEquals(SubscriptionActivity::class.java.name, target.intent.component?.className)
            assertFalse(PrefsHelper.isSubscriptionActive(activity))
        } finally {
            if (!activity.isDestroyed) controller.stop().destroy()
        }
    }

    @Test fun updateEntryOpensPlayStoreAndSubscriberHasNoPremiumOffer() {
        PrefsHelper.setSubscriptionActive(ctx, true)
        playUpdate(installed() + 1)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        val activity = controller.get()
        try {
            activity.findViewById<View>(R.id.btnNotifications).performClick()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertEquals(1, dialog.listView.adapter.count)
            assertTrue(dialog.listView.adapter.getItem(0).toString().contains("Atualização disponível"))
            dialog.listView.performItemClick(null, 0, 0)
            val target = shadowOf(activity).nextStartedActivity
            assertTrue(target.data.toString().contains("details?id=${activity.packageName}"))
            assertTrue(PrefsHelper.isSubscriptionActive(ctx))
        } finally {
            if (!activity.isDestroyed) controller.stop().destroy()
        }
    }

    @Test fun headerStillFitsWithBellInAllPalettes() {
        for (palette in listOf("original", "petroleo", "gold")) {
            ctx.getSharedPreferences("guardian_appearance", 0).edit().putString("palette", palette).commit()
            PrefsHelper.setSubscriptionActive(ctx, true)
            val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
            val activity = controller.get()
            try {
                MainActivity::class.java.getDeclaredMethod("refreshUI").apply { isAccessible = true }.invoke(activity)
                val title = activity.findViewById<TextView>(R.id.tvAppTitle)
                val tagline = activity.findViewById<TextView>(R.id.tvAppTagline)
                val bell = activity.findViewById<View>(R.id.btnNotifications)
                val header = title.parent.parent as View
                for (screenDp in listOf(320, 360, 402)) {
                    val width = ((screenDp - 40) * activity.resources.displayMetrics.density).toInt()
                    repeat(3) {
                        title.forceLayout()
                        tagline.forceLayout()
                        header.forceLayout()
                        header.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        header.layout(0, 0, header.measuredWidth, header.measuredHeight)
                    }
                    for (text in listOf(title, tagline)) {
                        text.draw(android.graphics.Canvas())
                        assertEquals("Uma linha em $palette/$screenDp", 1, text.lineCount)
                        assertEquals(text.text.length, text.layout.getLineEnd(0))
                        assertTrue("Sem transbordar em $palette/$screenDp: ${text.text}",
                            text.layout.getLineWidth(0) <= text.width + 1)
                    }
                    assertTrue(bell.width >= (48 * activity.resources.displayMetrics.density).toInt())
                }
            } finally {
                if (!activity.isDestroyed) controller.stop().destroy()
            }
        }
    }
}
