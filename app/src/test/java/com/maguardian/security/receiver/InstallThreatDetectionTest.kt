package com.maguardian.security.receiver

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import com.maguardian.security.R
import com.maguardian.security.service.InstallThreatCheckJobService
import com.maguardian.security.ui.MainActivity
import com.maguardian.security.ui.SubscriptionActivity
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
import java.util.concurrent.TimeUnit

/** Inert PackageManager fixtures only: no malicious APK is downloaded or run. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
class InstallThreatDetectionTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = ctx.getSystemService(NotificationManager::class.java)
    private val known = "com.gbwhatsapp"
    private val capabilities = arrayOf(Manifest.permission.SYSTEM_ALERT_WINDOW,
        Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.RECEIVE_BOOT_COMPLETED)

    @Before fun isolate() {
        ctx.getSharedPreferences("maguardian_prefs", 0).edit().clear()
            .putLong("trial_start_date", System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8)).commit()
        ctx.getSharedPreferences("guardian_install_checks", 0).edit().clear().commit()
        PrefsHelper.setProtectionEnabled(ctx, false)
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
        InstallThreatMonitor::class.java.getDeclaredField("registered").apply { isAccessible = true }
            .setBoolean(null, false)
    }

    private fun install(pkg: String, visible: Boolean = true, permissions: Array<String> = emptyArray(),
                        update: Long = 1L) {
        val app = ApplicationInfo().apply {
            packageName = pkg
            nonLocalizedLabel = "Fixture inerte"
        }
        shadowOf(ctx.packageManager).installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = app
            requestedPermissions = permissions
            firstInstallTime = 1
            lastUpdateTime = update
        })
        if (visible) {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            shadowOf(ctx.packageManager).addResolveInfoForIntent(launcher, ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = pkg
                    name = "$pkg.Launcher"
                    applicationInfo = app
                }
            })
        }
    }

    private fun latest(pkg: String = known): Notification =
        shadowOf(manager).getNotification(pkg.hashCode())!!

    @Test fun expiredUserGetsGenericAlertAndOnlySubscriptionAction() {
        install(known)
        PackageInstallReceiver().checkInstalled(ctx, known, false)
        assertEquals(1, PrefsHelper.getThreats(ctx).length())
        val notification = latest()
        val text = notification.extras.toString()
        assertFalse(text.contains(known))
        assertFalse(text.contains("Fixture inerte"))
        assertEquals(listOf("Seja Premium"), notification.actions.map { it.title.toString() })
        assertEquals(SubscriptionActivity::class.java.name,
            shadowOf(notification.actions.single().actionIntent).savedIntent.component!!.className)
        assertEquals(SubscriptionActivity::class.java.name,
            shadowOf(notification.contentIntent).savedIntent.component!!.className)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        try {
            val activity = controller.get()
            MainActivity::class.java.getDeclaredMethod("refreshUI")
                .apply { isAccessible = true }.invoke(activity)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.layoutThreatsLocked).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.llThreats).visibility)
            assertEquals(0, activity.findViewById<LinearLayout>(R.id.llThreats).childCount)
            assertFalse(PrefsHelper.hasFullAccess(activity))
            assertFalse(PrefsHelper.isProtectionEnabled(activity))
        } finally { controller.stop().destroy() }
    }

    @Test fun subscribersAndTrialKeepDetailsAndExistingUninstallAction() {
        install(known)
        for (trial in listOf(false, true)) {
            ctx.getSharedPreferences("maguardian_prefs", 0).edit().clear()
                .putLong("trial_start_date", if (trial) System.currentTimeMillis()
                    else System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8)).commit()
            PrefsHelper.setSubscriptionActive(ctx, !trial)
            PackageInstallReceiver().checkInstalled(ctx, known, false)
            val notification = latest()
            assertTrue(notification.extras.toString().contains(known))
            assertEquals(listOf("Desinstalar"), notification.actions.map { it.title.toString() })
            val uninstall = shadowOf(notification.actions.single().actionIntent).savedIntent
            assertEquals(Intent.ACTION_DELETE, uninstall.action)
            assertEquals("package:$known", uninstall.data.toString())
            manager.cancelAll()
        }
    }

    @Test fun hiddenCheckMatchesManualCriteriaButVisiblePermissionsAloneDoNotAlert() {
        install("org.fixture.hidden", false, capabilities)
        install("org.fixture.notes", true, capabilities)
        install("io.metamask", false, capabilities)
        val receiver = PackageInstallReceiver()
        for (pkg in listOf("org.fixture.hidden", "org.fixture.notes", "io.metamask")) {
            receiver.checkInstalled(ctx, pkg, false)
        }
        assertEquals(1, PrefsHelper.getThreats(ctx).length())
        assertEquals("org.fixture.hidden", PrefsHelper.getThreats(ctx).getJSONObject(0).getString("packageName"))
    }

    @Test fun keptMissingAndRepeatedPackagesDoNotGenerateAnotherAlert() {
        install(known)
        val receiver = PackageInstallReceiver()
        receiver.checkInstalled(ctx, known, false)
        manager.cancelAll()
        receiver.checkInstalled(ctx, known, true)
        assertEquals(0, shadowOf(manager).allNotifications.size)
        PrefsHelper.markThreatKept(ctx, known)
        receiver.checkInstalled(ctx, known, false)
        receiver.checkInstalled(ctx, "org.fixture.absent", false)
        assertEquals(0, shadowOf(manager).allNotifications.size)
        assertTrue(PrefsHelper.isThreatKept(ctx, known))
    }

    @Test fun automaticFallbackFindsNewAndUpdatedAppsWithoutManualScanOrPremiumUnlock() {
        InstallThreatMonitor.reconcile(ctx)
        install(known)
        InstallThreatMonitor.reconcile(ctx)
        assertEquals(1, PrefsHelper.getThreats(ctx).length())
        assertNotNull(latest())
        manager.cancelAll()
        InstallThreatMonitor.reconcile(ctx)
        assertEquals(0, shadowOf(manager).allNotifications.size)
        install("org.fixture.changing", true, emptyArray(), 1)
        InstallThreatMonitor.reconcile(ctx)
        install("org.fixture.changing", false, capabilities, 2)
        // Remove the previous launcher metadata to model an update hiding its icon.
        shadowOf(ctx.packageManager).removeResolveInfosForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), "org.fixture.changing")
        InstallThreatMonitor.reconcile(ctx)
        assertEquals(2, PrefsHelper.getThreats(ctx).length())
        assertFalse(PrefsHelper.hasFullAccess(ctx))
        assertFalse(PrefsHelper.isProtectionEnabled(ctx))
        assertEquals(0, PrefsHelper.getScanCount(ctx))
    }

    @Test fun dynamicReceiverAndPersistentOfflineFallbackAreRegistered() {
        InstallThreatMonitor.start(ctx)
        // Wait for the startup reconciliation to finish, before installing the fixture.
        InstallThreatMonitor.reconcile(ctx)
        val job = requireNotNull(ctx.getSystemService(JobScheduler::class.java)
            .getPendingJob(InstallThreatCheckJobService.JOB_ID))
        assertTrue(job.isPersisted)
        assertEquals(TimeUnit.MINUTES.toMillis(15), job.intervalMillis)
        assertEquals(android.app.job.JobInfo.NETWORK_TYPE_NONE, job.networkType)
        install(known)
        ctx.sendBroadcast(Intent(Intent.ACTION_PACKAGE_ADDED, Uri.parse("package:$known")))
        shadowOf(Looper.getMainLooper()).idle()
        val until = System.currentTimeMillis() + 5000
        while (PrefsHelper.getThreats(ctx).length() == 0 && System.currentTimeMillis() < until) {
            Thread.sleep(20)
        }
        assertEquals(1, PrefsHelper.getThreats(ctx).length())
    }

    @Test fun deniedNotificationsDoNotDiscardDetectionOrUnlockDetails() {
        install(known)
        shadowOf(manager).setNotificationsEnabled(false)
        PackageInstallReceiver().checkInstalled(ctx, known, false)
        assertEquals(1, PrefsHelper.getThreats(ctx).length())
        assertEquals(0, shadowOf(manager).allNotifications.size)
        assertFalse(PrefsHelper.hasFullAccess(ctx))
    }
}
