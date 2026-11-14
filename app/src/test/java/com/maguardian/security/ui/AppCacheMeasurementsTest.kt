package com.maguardian.security.ui

import android.app.AppOpsManager
import android.app.Application
import android.app.usage.StorageStats
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.os.Process
import android.os.storage.StorageManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.maguardian.security.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.android.controller.ActivityController
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppCacheMeasurementsTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    private val mb = 1024L * 1024L
    private val ownLabel: String get() = ctx.packageManager.getApplicationLabel(ctx.applicationInfo).toString()
    private val opened = mutableListOf<ActivityController<AppCacheActivity>>()

    private fun openActivity() =
        Robolectric.buildActivity(AppCacheActivity::class.java).setup().also { opened.add(it) }

    @After fun cancelWorkersEvenWhenAnAssertionFails() {
        opened.filterNot { it.get().isDestroyed }.forEach { it.destroy() }
    }

    private fun allowUsage(allowed: Boolean = true) {
        shadowOf(ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName,
            if (allowed) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED)
    }

    private fun packageInfo(pkg: String, label: String, visible: Boolean,
                            bytes: Long?, system: Boolean = false, enabled: Boolean = true) {
        val app = ApplicationInfo().apply {
            packageName = pkg
            nonLocalizedLabel = label
            this.enabled = enabled
            flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
        }
        shadowOf(ctx.packageManager).installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = app
        })
        if (visible) addLauncher(pkg, app)
        if (bytes != null) setCache(pkg, bytes)
    }

    private fun addLauncher(pkg: String, app: ApplicationInfo = ctx.packageManager.getApplicationInfo(pkg, 0)) {
        shadowOf(ctx.packageManager).addResolveInfoForIntent(launcher, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = "$pkg.Launcher"
                applicationInfo = app
                enabled = true
                exported = true
            }
        })
    }

    private fun setCache(pkg: String, bytes: Long) {
        val stats = ReflectionHelpers.callConstructor(StorageStats::class.java)
        ReflectionHelpers.setField(stats, "cacheBytes", bytes)
        // Large app/data values must not accidentally be included in a cache sum.
        ReflectionHelpers.setField(stats, "codeBytes", 9000 * mb)
        ReflectionHelpers.setField(stats, "dataBytes", 7000 * mb)
        shadowOf(ctx.getSystemService(StorageStatsManager::class.java)).addStorageStats(
            StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle(), stats)
    }

    private fun waitForMeasurements(activity: AppCacheActivity) {
        val button = activity.findViewById<Button>(R.id.btnCacheRefresh)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!button.isEnabled && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Consulta deve terminar", button.isEnabled)
        assertFalse(activity.findViewById<TextView>(R.id.tvAppCacheSummary).text.contains("Não foi possível"))
    }

    private fun labels(activity: AppCacheActivity): List<String> {
        val rows = activity.findViewById<LinearLayout>(R.id.llAppCaches)
        fun labels(view: android.view.View): List<String> {
            if (view is LinearLayout && view.childCount == 2 && view.getChildAt(0) is TextView &&
                view.getChildAt(0) !is android.widget.Button && view.getChildAt(1) is TextView) {
                return listOf((view.getChildAt(0) as TextView).text.toString())
            }
            if (view !is android.view.ViewGroup) return emptyList()
            return (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
        }
        return labels(rows)
    }

    private fun total(activity: AppCacheActivity) =
        activity.findViewById<TextView>(R.id.tvAppCacheTotal).text.toString()

    @Test fun onlyVisibleAppsAreCountedWithoutInternalPackagesOrDuplicateIcons() {
        allowUsage()
        // Guardian itself is also a real launchable app and belongs in the list.
        setCache(ctx.packageName, 0)
        packageInfo("org.example.notes", "Notas", true, mb)
        packageInfo("org.example.camera", "Câmera", true, 2 * mb, system = true)
        packageInfo("com.android.internal.fixture", "Serviço interno", false, 5000 * mb, system = true)
        packageInfo("org.example.provider", "Provedor de dados", false, 600 * mb)
        packageInfo("org.example.disabled", "Desativado", true, 200 * mb, enabled = false)
        addLauncher("org.example.notes") // multiple icons must still mean one app
        val controller = openActivity()
        val activity = controller.get()
        waitForMeasurements(activity)
        assertEquals(listOf("Câmera", "Notas", ownLabel), labels(activity))
        assertEquals("3,0 MB", total(activity))
        assertTrue(activity.findViewById<TextView>(R.id.tvAppCacheSummary).text.startsWith("3 aplicativos"))
        controller.pause().stop().destroy()
    }

    @Test fun unavailableSizeIsNotInventedAndReturningRecalculatesTheSameVisibleApps() {
        allowUsage()
        setCache(ctx.packageName, 0)
        packageInfo("org.example.notes", "Notas", true, 4 * mb)
        packageInfo("org.example.unavailable", "Sem acesso", true, null)
        val controller = openActivity()
        var activity = controller.get()
        waitForMeasurements(activity)
        assertEquals("4,0 MB", total(activity))
        assertEquals(listOf("Notas", ownLabel, "Sem acesso"), labels(activity))
        assertTrue(activity.findViewById<TextView>(R.id.tvAppCacheSummary).text.contains("1 aplicativo sem tamanho disponível"))
        controller.pause().stop()
        setCache("org.example.notes", 0)
        controller.start().resume()
        activity = controller.get()
        waitForMeasurements(activity)
        assertEquals("0 MB", total(activity))
        assertEquals(3, labels(activity).size)
        controller.pause().stop().destroy()
    }

    @Test fun usagePermissionIsStillRequiredForOtherApps() {
        allowUsage(false)
        packageInfo("org.example.notes", "Notas", true, 900 * mb)
        setCache(ctx.packageName, mb)
        val controller = openActivity()
        val activity = controller.get()
        waitForMeasurements(activity)
        assertTrue(labels(activity).contains("Notas"))
        assertEquals("1,0 MB", total(activity))
        fun texts(view: android.view.View): List<String> =
            if (view is TextView) listOf(view.text.toString())
            else if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
            else emptyList()
        assertTrue(texts(activity.findViewById(R.id.llAppCaches)).contains("Tamanho indisponível"))
        assertFalse(texts(activity.findViewById(android.R.id.content)).contains("Autorizar acesso de uso"))
        assertTrue(activity.findViewById<TextView>(R.id.tvAppCacheSummary).text.contains("Acesso de uso não autorizado"))
        controller.pause().stop().destroy()
    }
}
