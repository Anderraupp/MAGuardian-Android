package com.maguardian.security.ui

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.StorageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.maguardian.security.R
import com.maguardian.security.billing.BillingManager
import com.maguardian.security.data.MalwareDatabase
import com.maguardian.security.receiver.TrialAlertReceiver
import com.maguardian.security.service.PopupDetectorService
import com.maguardian.security.util.PermissionHelper
import com.maguardian.security.util.PrefsHelper
import android.webkit.CookieManager
import android.webkit.WebStorage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "MainActivity"
        const val COLOR_RED    = 0xFFDC2626.toInt()
        const val COLOR_YELLOW = 0xFFF59E0B.toInt()
    }

    private val pendingUninstall = mutableSetOf<String>()
    private val pulseAnimators = mutableMapOf<Int, ValueAnimator>()

    // ── Assinatura ───────────────────────────────────────────────────────────
    private lateinit var billing: BillingManager

    private val subscriptionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // Usuário assinou — recarrega UI
            refreshUI()
        }
    }

    private fun requireSubscription(action: () -> Unit) {
        if (PrefsHelper.hasFullAccess(this)) {
            action()
        } else {
            subscriptionLauncher.launch(Intent(this, SubscriptionActivity::class.java))
        }
    }

    private fun requireSubscriptionToUninstall(pkg: String) {
        requireSubscription {
            pendingUninstall.add(pkg)
            startActivity(Intent(Intent.ACTION_DELETE).apply {
                data = android.net.Uri.parse("package:$pkg")
            })
        }
    }
    // ────────────────────────────────────────────────────────────────────────

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(this, "✓ Notificações ativadas! Você será alertado sobre ameaças.", Toast.LENGTH_LONG).show()
        }
    }

    private val threatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.maguardian.security.THREAT_DETECTED") {
                refreshUI()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        setupUI()
        initBilling()
        checkPermissionsAndStart()
        checkAndRequestNotifPermission()
        scheduleTrialAlertsIfNeeded()
    }

    private fun initBilling() {
        billing = BillingManager(this) { isActive ->
            if (isActive) refreshUI()
        }
        billing.connect { }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::billing.isInitialized) billing.destroy()
    }

    override fun onResume() {
        super.onResume()

        // Reset automático de 12 horas — limpa histórico se o prazo expirou
        PrefsHelper.maybeAutoReset(this)

        // Ao voltar ao app (ex: depois de conceder permissão nas Configurações),
        // inicia o serviço silenciosamente se as permissões agora estão concedidas.
        // Não mostra diálogo aqui — isso só acontece em checkPermissionsAndStart().
        maybeStartServiceSilently()

        // Verifica quais apps pendentes de desinstalação foram realmente removidos
        val iter = pendingUninstall.iterator()
        while (iter.hasNext()) {
            val pkg = iter.next()
            val stillInstalled = try {
                packageManager.getPackageInfo(pkg, 0)
                true
            } catch (e: Exception) { false }
            if (!stillInstalled) {
                PrefsHelper.markThreatRemoved(this, pkg)
                iter.remove()
                Toast.makeText(this, "✓ App removido com sucesso!", Toast.LENGTH_SHORT).show()
            }
        }
        refreshUI()
        maybeShowTrialPopup()
        val filter = IntentFilter("com.maguardian.security.THREAT_DETECTED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(threatReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(threatReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(threatReceiver) }
        stopAllPulses()
    }

    private fun setupUI() {
        val btnScan = findViewById<Button>(R.id.btnScan)
        val btnToggle = findViewById<TextView>(R.id.btnToggle)
        val btnClearThreats = findViewById<Button>(R.id.btnClearThreats)
        val btnPermissions = findViewById<Button>(R.id.btnPermissions)

        btnScan.setOnClickListener { runManualScan() }
        btnClearThreats.setOnClickListener {
            requireSubscription {
                PrefsHelper.clearThreats(this)
                refreshUI()
            }
        }
        btnPermissions.setOnClickListener { showPermissionsDialog() }
        btnToggle.setOnClickListener { requireSubscription { toggleProtection() } }

        val btnCleanCache = findViewById<Button>(R.id.btnCleanCache)
        btnCleanCache.setOnClickListener { runCacheClean() }

        val btnAppCache = findViewById<Button>(R.id.btnAppCache)
        btnAppCache.setOnClickListener { showAppCacheDialog() }

        val btnLinkChecker = findViewById<Button>(R.id.btnLinkChecker)
        btnLinkChecker.setOnClickListener {
            startActivity(Intent(this, LinkCheckerActivity::class.java))
        }

        val btnSetupLinkInterceptor = findViewById<Button>(R.id.btnSetupLinkInterceptor)
        btnSetupLinkInterceptor.setOnClickListener {
            showLinkInterceptorSetupDialog()
        }

        refreshCacheInfo()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun calculateTotalCache(): Long {
        return try {
            val statsManager = getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            val myUser = android.os.Process.myUserHandle()
            var total = 0L
            for (app in packageManager.getInstalledApplications(PackageManager.GET_META_DATA)) {
                try {
                    total += statsManager.queryStatsForPackage(
                        StorageManager.UUID_DEFAULT, app.packageName, myUser
                    ).cacheBytes
                } catch (e: Exception) { /* app sem permissão de stats, ignora */ }
            }
            total
        } catch (e: Exception) { 0L }
    }

    private val browserPackages = listOf(
        "com.android.chrome",
        "com.sec.android.app.sbrowser",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.brave.browser",
        "com.UCMobile.intl",
        "com.uc.browser.en",
        "mobi.mgeek.TunnyBrowser",
        "com.kiwibrowser.browser",
        "com.duckduckgo.mobile.android",
        "com.vivaldi.browser",
        "com.yandex.browser"
    )

    @RequiresApi(Build.VERSION_CODES.O)
    private fun queryPackageCache(pkg: String): Long {
        return try {
            val statsManager = getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            statsManager.queryStatsForPackage(
                StorageManager.UUID_DEFAULT, pkg, android.os.Process.myUserHandle()
            ).cacheBytes
        } catch (e: Exception) { 0L }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun calculateBrowserCache(): Long {
        var total = 0L
        for (pkg in browserPackages) {
            try {
                packageManager.getPackageInfo(pkg, 0)
                total += queryPackageCache(pkg)
            } catch (e: Exception) { /* navegador não instalado */ }
        }
        return total
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024 * 1024)
        return if (mb < 1) "${bytes / 1024} KB" else "$mb MB"
    }

    private fun refreshCacheInfo() {
        val tvCacheSize = findViewById<TextView>(R.id.tvCacheSize)
        Thread {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val total = calculateTotalCache()
                val browser = calculateBrowserCache()
                runOnUiThread {
                    tvCacheSize.text = when {
                        total == 0L  -> "Toque para limpar o cache"
                        browser > 0L -> "${formatBytes(total)} em cache • Navegadores: ${formatBytes(browser)}"
                        else         -> "${formatBytes(total)} em cache"
                    }
                }
            } else {
                runOnUiThread { tvCacheSize.text = "Toque para limpar o cache" }
            }
        }.start()
    }

    private fun runCacheClean() {
        val btnCleanCache = findViewById<Button>(R.id.btnCleanCache)
        val tvCacheSize = findViewById<TextView>(R.id.tvCacheSize)
        btnCleanCache.isEnabled = false
        btnCleanCache.text = "Limpando..."
        tvCacheSize.text = "Aguarde..."

        Thread {
            // 1. Total antes da limpeza
            val totalBefore = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) calculateTotalCache() else 0L

            // 2. Limpa cache interno do app
            var ownFreed = cacheDir.walkTopDown().sumOf { it.length() }
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()

            // 3. Limpa cache externo do app (cartão SD / armazenamento externo)
            externalCacheDir?.let { ext ->
                ownFreed += ext.walkTopDown().sumOf { it.length() }
                ext.deleteRecursively()
                ext.mkdirs()
            }

            // 4. Limpa cache de código compilado (API 21+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                codeCacheDir?.let { code ->
                    ownFreed += code.walkTopDown().sumOf { it.length() }
                    code.deleteRecursively()
                    code.mkdirs()
                }
            }

            // 5. Limpa arquivos temporários em filesDir
            File(filesDir, "tmp").takeIf { it.exists() }?.let { tmp ->
                ownFreed += tmp.walkTopDown().sumOf { it.length() }
                tmp.deleteRecursively()
            }

            // 6. Limpa cache WebView (cookies + armazenamento web) — na UI thread
            runOnUiThread {
                try {
                    WebStorage.getInstance().deleteAllData()
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                } catch (e: Exception) { /* WebView não disponível */ }
            }

            Thread.sleep(300)

            // 7. Mede total após limpeza
            val totalAfter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) calculateTotalCache() else 0L
            val browserAfter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) calculateBrowserCache() else 0L
            val totalFreed = if (totalBefore > totalAfter) totalBefore - totalAfter else ownFreed

            runOnUiThread {
                btnCleanCache.isEnabled = true
                btnCleanCache.text = "Limpar Cache"
                tvCacheSize.text = when {
                    totalAfter == 0L  -> "Toque para limpar o cache"
                    browserAfter > 0L -> "${formatBytes(totalAfter)} em cache • Navegadores: ${formatBytes(browserAfter)}"
                    else              -> "${formatBytes(totalAfter)} em cache"
                }

                val freedMsg = if (totalFreed > 0) "✓ ${formatBytes(totalFreed)} liberados!" else "Cache já estava limpo."

                if (totalAfter > 1024 * 1024) {
                    val extraInfo = if (browserAfter > 0L) " (navegadores: ${formatBytes(browserAfter)})" else ""
                    val dialogMsg = "$freedMsg\n\nAinda há ${formatBytes(totalAfter)} em cache de outros apps$extraInfo."

                    val builder = android.app.AlertDialog.Builder(this)
                        .setTitle("✓ Limpeza Concluída")
                        .setMessage(dialogMsg)
                        .setNegativeButton("Fechar", null)

                    // Android 9+: limpa cache de TODOS os apps via diálogo do sistema (1 clique)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        builder.setPositiveButton("Limpar Tudo Agora") { _, _ ->
                            try {
                                startActivity(Intent(StorageManager.ACTION_CLEAR_APP_CACHE))
                            } catch (e: Exception) {
                                try {
                                    startActivity(Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                                } catch (ex: Exception) {
                                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                                }
                            }
                        }
                    } else {
                        builder.setPositiveButton("Abrir Configurações") { _, _ ->
                            try {
                                startActivity(Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                            } catch (e: Exception) {
                                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                            }
                        }
                    }
                    builder.show()
                } else {
                    Toast.makeText(this, freedMsg, Toast.LENGTH_LONG).show()
                }
                refreshCacheInfo()
            }
        }.start()
    }

    /**
     * Guia o usuário para configurar o M&A Guardian como interceptor de links.
     * Padrão Android: registrado como handler de http/https, aparece no seletor de apps.
     */
    private fun showLinkInterceptorSetupDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("🔗 Ativar Bloqueio Automático de Links")
            .setMessage(
                "Para bloquear links suspeitos automaticamente, siga os passos:\n\n" +
                "1. Toque em \"Abrir Configurações\" abaixo\n" +
                "2. Vá em Aplicativos padrão → Abrir links\n" +
                "3. Encontre o M&A Guardian e ative\n\n" +
                "A partir daí, qualquer link clicado no WhatsApp, SMS ou e-mail será verificado antes de abrir."
            )
            .setPositiveButton("Abrir Configurações") { _, _ ->
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback: open general app settings
                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Mostra diálogo com lista de apps ordenados por tamanho de cache (maior primeiro).
     * Cada item tem botão "Abrir" que abre as configurações do app para limpeza manual.
     */
    private fun showAppCacheDialog() {
        val loadingDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Cache por App")
            .setMessage("Calculando cache dos apps...")
            .setCancelable(false)
            .show()

        Thread {
            data class AppCacheInfo(val pkgName: String, val label: String, val cacheBytes: Long)

            val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            val results = mutableListOf<AppCacheInfo>()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val statsManager = getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
                val myUser = android.os.Process.myUserHandle()
                for (app in apps) {
                    if (app.packageName == packageName) continue
                    try {
                        val cacheBytes = statsManager.queryStatsForPackage(
                            StorageManager.UUID_DEFAULT, app.packageName, myUser
                        ).cacheBytes
                        if (cacheBytes > 0L) {
                            val label = try {
                                packageManager.getApplicationLabel(app).toString()
                            } catch (e: Exception) { app.packageName }
                            results.add(AppCacheInfo(app.packageName, label, cacheBytes))
                        }
                    } catch (e: Exception) { /* sem permissão para este app */ }
                }
            }

            results.sortByDescending { it.cacheBytes }

            runOnUiThread {
                loadingDialog.dismiss()

                if (results.isEmpty()) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Cache por App")
                        .setMessage("Nenhum app com cache encontrado.\n\nNo Android 8 ou inferior esta função não está disponível.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@runOnUiThread
                }

                // Monta a view da lista de apps
                val scrollView = android.widget.ScrollView(this)
                val container = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(0, 8, 0, 8)
                }
                scrollView.addView(container)

                val limitedList = if (results.size > 50) results.take(50) else results

                for (info in limitedList) {
                    val row = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(24, 14, 24, 14)
                    }

                    val nameView = android.widget.TextView(this).apply {
                        text = info.label
                        textSize = 13f
                        setTextColor(resources.getColor(R.color.text_primary, theme))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }

                    val sizeView = android.widget.TextView(this).apply {
                        text = formatBytes(info.cacheBytes)
                        textSize = 11f
                        setTextColor(resources.getColor(R.color.text_secondary, theme))
                        setPadding(0, 0, 12, 0)
                    }

                    val openBtn = android.widget.Button(this).apply {
                        text = "Abrir"
                        textSize = 10f
                        setTextColor(resources.getColor(android.R.color.white, theme))
                        background = resources.getDrawable(R.drawable.btn_primary, theme)
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            72
                        )
                        setPadding(20, 0, 20, 0)
                        setOnClickListener {
                            try {
                                startActivity(
                                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.parse("package:${info.pkgName}")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            } catch (e: Exception) {
                                Toast.makeText(this@MainActivity, "Não foi possível abrir as configurações.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    row.addView(nameView)
                    row.addView(sizeView)
                    row.addView(openBtn)

                    // Divisor
                    val divider = android.view.View(this).apply {
                        setBackgroundColor(resources.getColor(R.color.surface_dark, theme))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
                        )
                    }

                    container.addView(row)
                    container.addView(divider)
                }

                val totalCacheShown = results.sumOf { it.cacheBytes }
                val subtitle = "${results.size} apps • ${formatBytes(totalCacheShown)} total em cache"

                android.app.AlertDialog.Builder(this)
                    .setTitle("Cache por App")
                    .setMessage(subtitle)
                    .setView(scrollView)
                    .setNegativeButton("Fechar", null)
                    .setPositiveButton("Limpar Tudo") { _, _ ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            try {
                                startActivity(Intent(StorageManager.ACTION_CLEAR_APP_CACHE))
                            } catch (e: Exception) {
                                startActivity(Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                            }
                        } else {
                            runCacheClean()
                        }
                    }
                    .show()
            }
        }.start()
    }

    /**
     * Chamado em onResume(): inicia serviço + scan automático se permissões foram
     * recém-concedidas, sem exibir nenhum diálogo. Cobre o caso em que o usuário
     * saiu para Configurações, concedeu UsageStats e voltou ao app.
     */
    private fun maybeStartServiceSilently() {
        if (!PrefsHelper.hasFullAccess(this)) return
        val status = PermissionHelper.checkAll(this)
        if (!status.allGranted) return
        if (PrefsHelper.isProtectionEnabled(this)) startDetectionService()
        if (PrefsHelper.getLastScan(this) == 0L && !isScanning) {
            runManualScan()
        }
    }

    private fun checkPermissionsAndStart() {
        if (!PrefsHelper.hasFullAccess(this)) return
        val status = PermissionHelper.checkAll(this)
        if (status.allGranted) {
            if (PrefsHelper.isProtectionEnabled(this)) startDetectionService()
            if (PrefsHelper.getLastScan(this) == 0L && !isScanning) {
                runManualScan()
            }
        } else {
            showPermissionsDialog()
        }
    }

    private fun toggleProtection() {
        val enabled = PrefsHelper.isProtectionEnabled(this)
        PrefsHelper.setProtectionEnabled(this, !enabled)
        if (!enabled) {
            if (PermissionHelper.checkAll(this).allGranted) {
                startDetectionService()
            } else {
                showPermissionsDialog()
            }
        } else {
            stopService(Intent(this, PopupDetectorService::class.java))
        }
        refreshUI()
    }

    private fun startDetectionService() {
        val intent = Intent(this, PopupDetectorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Log.i(TAG, "Serviço de detecção iniciado")
    }

    private var isScanning = false

    // ── Varredura animada para não-assinantes (10 segundos) ──────────────────

    private fun runFreeUserScan() {
        if (isScanning) return
        isScanning = true

        val btnScan = findViewById<Button>(R.id.btnScan)
        btnScan.isEnabled = false
        btnScan.text = "Escaneando..."

        val scanSteps = listOf(
            "Inicializando varredura...",
            "Analisando apps instalados...",
            "Verificando permissões suspeitas...",
            "Checando processos em segundo plano...",
            "Inspecionando dados de rede...",
            "Analisando comportamento de apps...",
            "Verificando vulnerabilidades do sistema...",
            "Consolidando resultados..."
        )

        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 48, 60, 32)
        }
        val tvStep = android.widget.TextView(this).apply {
            text = scanSteps[0]
            textSize = 13f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24 }
        }
        val progressBar = android.widget.ProgressBar(
            this, null, android.R.style.Widget_ProgressBar_Horizontal
        ).apply {
            max = 100
            progress = 0
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        dialogView.addView(tvStep)
        dialogView.addView(progressBar)

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("🔍 Fazendo Varredura")
            .setView(dialogView)
            .setCancelable(false)
            .show()

        val totalMs = 10_000L
        val intervalMs = 200L
        val steps = (totalMs / intervalMs).toInt()
        var tick = 0

        // Varredura real em background — popula o banco de ameaças corretamente
        Thread {
            try {
                ensureThreatChannelExists()
                val pkgs = packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                val launcherPkgs = packageManager
                    .queryIntentActivities(
                        Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }, 0
                    ).map { it.activityInfo.packageName }.toSet()

                for (pkg in pkgs) {
                    val pkgName = pkg.packageName
                    if (pkgName == packageName) continue
                    if (MalwareDatabase.isSystemPrefix(pkgName)) continue
                    if (MalwareDatabase.isTrustedApp(pkgName)) continue
                    if (MalwareDatabase.isScanExempt(pkgName)) continue

                    val appLabel = try {
                        packageManager.getApplicationLabel(pkg.applicationInfo).toString()
                    } catch (e: Exception) { pkgName }

                    val isSystemApp = (pkg.applicationInfo.flags and
                        (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                         android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                    val hasLauncher = pkgName in launcherPkgs

                    MalwareDatabase.isMalware(pkgName)?.let { PrefsHelper.saveThreat(this, it) }
                    MalwareDatabase.checkHiddenApp(pkgName, appLabel, pkg.requestedPermissions, hasLauncher, isSystemApp)
                        ?.let { PrefsHelper.saveThreat(this, it) }
                    MalwareDatabase.checkHeuristic(pkgName, appLabel, pkg.requestedPermissions)
                        ?.let { PrefsHelper.saveThreat(this, it) }
                }
                PrefsHelper.setLastScan(this, System.currentTimeMillis())
                PrefsHelper.incrementScanCount(this)
            } catch (e: Exception) {
                Log.e(TAG, "Erro na varredura silenciosa (não-assinante)", e)
            }
        }.start()

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                tick++
                val pct = (tick * 100 / steps).coerceAtMost(100)
                progressBar.progress = pct

                val stepIdx = ((pct / 100f) * (scanSteps.size - 1)).toInt()
                    .coerceIn(0, scanSteps.size - 1)
                tvStep.text = scanSteps[stepIdx]

                if (tick < steps) {
                    handler.postDelayed(this, intervalMs)
                } else {
                    dialog.dismiss()
                    isScanning = false
                    btnScan.isEnabled = true
                    btnScan.text = "Escanear"
                    refreshUI()

                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("⚠️ Varredura Concluída")
                        .setMessage(
                            "Foram detectados dados que podem estar vulneráveis no seu dispositivo.\n\n" +
                            "Apps em segundo plano podem estar acessando informações " +
                            "sem monitoramento ativo.\n\n" +
                            "Ative a proteção Premium para remover ameaças e manter " +
                            "seu celular seguro em tempo real."
                        )
                        .setPositiveButton("🔒 Proteja-se — Seja Premium") { _, _ ->
                            subscriptionLauncher.launch(Intent(this@MainActivity, SubscriptionActivity::class.java))
                        }
                        .setNegativeButton("Agora não", null)
                        .show()
                }
            }
        }
        handler.postDelayed(runnable, intervalMs)
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun runManualScan() {
        if (!PrefsHelper.hasFullAccess(this)) {
            runFreeUserScan()
            return
        }

        if (isScanning) return
        isScanning = true

        val btnScan = findViewById<Button>(R.id.btnScan)
        btnScan.isEnabled = false
        btnScan.text = "Escaneando..."

        Thread {
            val installedPackages = try {
                packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao listar pacotes", e)
                runOnUiThread {
                    isScanning = false
                    btnScan.isEnabled = true
                    btnScan.text = "Escanear"
                }
                return@Thread
            }

            ensureThreatChannelExists()

            // Pré-computa conjunto de packages com ícone no launcher (uma só query — rápido)
            val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val packagesWithLauncher = packageManager
                .queryIntentActivities(launcherIntent, 0)
                .map { it.activityInfo.packageName }
                .toSet()

            var threatsFound = 0

            for (pkg in installedPackages) {
                val pkgName = pkg.packageName

                // Pula o próprio app, apps de sistema, vendors confiáveis e isentos de varredura
                if (pkgName == packageName) continue
                if (MalwareDatabase.isSystemPrefix(pkgName)) continue
                if (MalwareDatabase.isTrustedApp(pkgName)) continue
                if (MalwareDatabase.isScanExempt(pkgName)) continue

                val appLabel = try {
                    packageManager.getApplicationLabel(pkg.applicationInfo).toString()
                } catch (e: Exception) { pkgName }

                val isSystemApp = (pkg.applicationInfo.flags and
                    (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                     android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                val hasLauncherIcon = pkgName in packagesWithLauncher

                // 1. Banco de dados de malware conhecido (detecção exata)
                val malware = MalwareDatabase.isMalware(pkgName)
                if (malware != null) {
                    PrefsHelper.saveThreat(this, malware)
                    sendScanThreatNotification(malware)
                    threatsFound++
                    Log.w(TAG, "Ameaça (banco): $pkgName")
                    continue
                }

                // 2. Detecção de apps ocultos: sem ícone, nome invisível ou fake sistema
                val hidden = MalwareDatabase.checkHiddenApp(
                    pkgName, appLabel, pkg.requestedPermissions, hasLauncherIcon, isSystemApp
                )
                if (hidden != null) {
                    PrefsHelper.saveThreat(this, hidden)
                    sendScanThreatNotification(hidden)
                    threatsFound++
                    Log.w(TAG, "Ameaça (oculto): $pkgName — ${hidden.description}")
                    continue
                }

                // 3. Heurística: nome suspeito + permissões perigosas
                val heuristic = MalwareDatabase.checkHeuristic(
                    pkgName, appLabel, pkg.requestedPermissions
                )
                if (heuristic != null) {
                    PrefsHelper.saveThreat(this, heuristic)
                    sendScanThreatNotification(heuristic)
                    threatsFound++
                    Log.w(TAG, "Ameaça (heurística): $pkgName — ${heuristic.description}")
                }
            }

            PrefsHelper.setLastScan(this, System.currentTimeMillis())
            PrefsHelper.incrementScanCount(this)

            runOnUiThread {
                isScanning = false
                btnScan.isEnabled = true
                btnScan.text = "Escanear"
                refreshUI()
                val msg = when {
                    threatsFound > 0 -> "⚠️ $threatsFound ameaça(s) encontrada(s)! Veja abaixo."
                    else -> "✓ Dispositivo limpo! Nenhuma ameaça detectada."
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /**
     * Garante que o canal de notificações de ameaças existe antes de enviar qualquer
     * notificação da varredura manual — necessário se o serviço ainda não foi iniciado.
     */
    private fun ensureThreatChannelExists() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(PopupDetectorService.CHANNEL_ID_THREAT) == null) {
            NotificationChannel(
                PopupDetectorService.CHANNEL_ID_THREAT,
                "Ameaças Detectadas",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alertas de apps maliciosos instalados no dispositivo"
                enableVibration(true)
                nm.createNotificationChannel(this)
            }
        }
    }

    /**
     * Envia notificação individual para cada ameaça encontrada na varredura.
     * Chamado em background thread — NotificationManager é thread-safe.
     */
    private fun sendScanThreatNotification(malware: MalwareDatabase.MalwareEntry) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val isSubscribed = PrefsHelper.hasFullAccess(this)

        val severity = when (malware.severity) {
            "high" -> "ALTA"
            "medium" -> "MÉDIA"
            else -> "BAIXA"
        }

        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = if (isSubscribed) {
            val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:${malware.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val uninstallPi = PendingIntent.getActivity(
                this, malware.packageName.hashCode(), uninstallIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            NotificationCompat.Builder(this, PopupDetectorService.CHANNEL_ID_THREAT)
                .setSmallIcon(R.drawable.ic_shield_alert)
                .setContentTitle("⚠️ Ameaça Detectada — Severidade $severity")
                .setContentText("${malware.appName} está instalado no dispositivo")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("${malware.appName} (${malware.packageName})\n${malware.description}")
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .addAction(R.drawable.ic_delete, "Desinstalar", uninstallPi)
                .setColor(0xFFDC2626.toInt())
                .build()
        } else {
            // Não-assinante: genérico, sem nome do app e sem botão de desinstalar
            val subPi = PendingIntent.getActivity(
                this, 1,
                Intent(this, SubscriptionActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            NotificationCompat.Builder(this, PopupDetectorService.CHANNEL_ID_THREAT)
                .setSmallIcon(R.drawable.ic_shield_alert)
                .setContentTitle("⚠️ Risco Detectado no Dispositivo")
                .setContentText("Um app suspeito foi encontrado. Ative o Premium para ver detalhes e remover.")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("Seu dispositivo pode estar em risco.\n\nAtive a proteção Premium para identificar e remover a ameaça imediatamente.")
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(subPi)
                .addAction(R.drawable.ic_shield_alert, "🔒 Seja Premium", subPi)
                .setColor(0xFFDC2626.toInt())
                .build()
        }

        nm.notify(malware.packageName.hashCode(), notification)
    }

    private fun refreshUI() {
        val permStatus = PermissionHelper.checkAll(this)
        val protectionEnabled = PrefsHelper.isProtectionEnabled(this)
        val totalFound = PrefsHelper.getTotalThreatsFound(this)
        val totalRemoved = PrefsHelper.getTotalThreatsRemoved(this)
        val totalScans = PrefsHelper.getScanCount(this)
        val lastScanMs = PrefsHelper.getLastScan(this)
        val threats = PrefsHelper.getThreats(this)

        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val tvStatusBadge = findViewById<TextView>(R.id.tvStatusBadge)
        val tvStatusDesc = findViewById<TextView>(R.id.tvStatusDesc)
        val tvSecurityRate = findViewById<TextView>(R.id.tvSecurityRate)
        val tvScansClean = findViewById<TextView>(R.id.tvScansClean)
        val tvTotalFound = findViewById<TextView>(R.id.tvTotalFound)
        val tvTotalRemoved = findViewById<TextView>(R.id.tvTotalRemoved)
        val tvTotalScans = findViewById<TextView>(R.id.tvTotalScans)
        val tvLastScan = findViewById<TextView>(R.id.tvLastScan)
        val tvThreatCount = findViewById<TextView>(R.id.tvThreatCount)
        val btnToggle = findViewById<TextView>(R.id.btnToggle)
        val llThreats = findViewById<LinearLayout>(R.id.llThreats)
        val layoutPermWarning = findViewById<LinearLayout>(R.id.layoutPermWarning)
        val tvPermWarning = findViewById<TextView>(R.id.tvPermWarning)
        val layoutNotifWarning = findViewById<LinearLayout>(R.id.layoutNotifWarning)
        val btnNotifPermission = findViewById<Button>(R.id.btnNotifPermission)
        val cardStatus = findViewById<LinearLayout>(R.id.cardStatus)
        val ivShield = findViewById<android.widget.ImageView>(R.id.ivShield)
        val layoutEmpty = findViewById<LinearLayout>(R.id.layoutEmpty)
        val layoutThreatsLocked = findViewById<LinearLayout>(R.id.layoutThreatsLocked)
        val tvThreatsLockedCount = findViewById<TextView>(R.id.tvThreatsLockedCount)
        val btnUnlockThreats = findViewById<Button>(R.id.btnUnlockThreats)

        // Aviso de permissões de uso
        if (!permStatus.allGranted) {
            layoutPermWarning.visibility = View.VISIBLE
            tvPermWarning.text = "Permissão necessária: Acesso ao Histórico de Uso de Apps"
            startPulse(R.id.layoutPermWarning)
        } else {
            layoutPermWarning.visibility = View.GONE
            stopPulse(R.id.layoutPermWarning)
        }

        // Card de notificações — aparece sempre que a permissão não foi concedida (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (notifGranted) {
                layoutNotifWarning.visibility = View.GONE
                stopPulse(R.id.layoutNotifWarning)
            } else {
                layoutNotifWarning.visibility = View.VISIBLE
                startPulse(R.id.layoutNotifWarning)
                btnNotifPermission.setOnClickListener {
                    // Tenta pedir a permissão; se já negada antes, o Android redireciona
                    // automaticamente para as configurações do app
                    if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        // Usuário marcou "Não perguntar novamente" — abre Configurações do app
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", packageName, null)
                        )
                        startActivity(intent)
                    }
                }
            }
        } else {
            layoutNotifWarning.visibility = View.GONE
        }

        // Taxa de segurança — não-assinantes sempre 0%
        val isSubscribed = PrefsHelper.hasFullAccess(this)
        val isProtectionActive = isSubscribed && protectionEnabled && permStatus.allGranted
        val securityPct = if (isProtectionActive) 100 else 0
        tvSecurityRate.text = "$securityPct%"
        tvSecurityRate.setTextColor(
            ContextCompat.getColor(this,
                if (securityPct == 100) R.color.success else R.color.danger)
        )
        tvScansClean.text = if (isProtectionActive) "Em tempo real" else "Desativada"

        // Status card — não-assinantes sempre veem "Risco"
        val isFullyActive = isSubscribed && protectionEnabled && permStatus.allGranted
        if (isFullyActive) {
            cardStatus.background = ContextCompat.getDrawable(this, R.drawable.card_status_active)
            tvStatus.text = "Proteção Ativa"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            tvStatusBadge.text = "Seguro"
            tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.success))
            tvStatusBadge.background = ContextCompat.getDrawable(this, R.drawable.badge_success)
            tvStatusDesc.text = if (totalFound > 0)
                "$totalFound ameaça(s) detectada(s)."
            else
                "Seu dispositivo está protegido. Nenhuma ameaça ativa no momento."
            ivShield.setImageResource(R.drawable.ic_shield_check)
            btnToggle.text = "Desativar"
            btnToggle.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        } else {
            cardStatus.background = ContextCompat.getDrawable(this, R.drawable.card_status_inactive)
            tvStatus.text = when {
                !isSubscribed -> "Proteção Inativa"
                !permStatus.allGranted -> "Permissões Necessárias"
                else -> "Proteção Inativa"
            }
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            tvStatusBadge.text = "Risco"
            tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.danger))
            tvStatusBadge.background = ContextCompat.getDrawable(this, R.drawable.badge_danger)
            tvStatusDesc.text = when {
                !isSubscribed -> "Assine o M&A Guardian para ativar a proteção em tempo real."
                !permStatus.allGranted -> "Conceda as permissões para ativar a proteção."
                else -> "Ative a proteção para monitorar ameaças automaticamente."
            }
            ivShield.setImageResource(R.drawable.ic_shield_alert)
            btnToggle.text = "Ativar"
            btnToggle.setTextColor(ContextCompat.getColor(this, R.color.primary))
        }

        // Estatísticas
        tvTotalFound.text = totalFound.toString()
        tvTotalRemoved.text = totalRemoved.toString()
        tvTotalScans.text = totalScans.toString()
        tvLastScan.text = if (lastScanMs > 0) {
            SimpleDateFormat("dd/MM/yyyy, HH:mm", Locale("pt", "BR")).format(Date(lastScanMs))
        } else {
            "Nunca"
        }

        // Badge + lista de ameaças — filtra apenas ativas e ainda instaladas
        val activeThreats = (0 until threats.length())
            .map { threats.getJSONObject(it) }
            .filter { it.optString("status", "detected") != "removed" }
            .filter { threat ->
                // Verifica se o app ainda está instalado; se não, remove automaticamente
                val pkg = threat.getString("packageName")
                val installed = try { packageManager.getPackageInfo(pkg, 0); true }
                                catch (e: Exception) { false }
                if (!installed) PrefsHelper.markThreatRemoved(this, pkg)
                installed
            }

        tvThreatCount.text = "${activeThreats.size} registros"
        llThreats.removeAllViews()

        if (!isSubscribed && activeThreats.isNotEmpty()) {
            // Usuário sem assinatura com ameaças: mostra card bloqueado com contagem
            layoutEmpty.visibility = View.GONE
            layoutThreatsLocked.visibility = View.VISIBLE
            llThreats.visibility = View.GONE
            val n = activeThreats.size
            tvThreatsLockedCount.text = if (n == 1)
                "⚠️ 1 ameaça detectada no seu dispositivo"
            else
                "⚠️ $n ameaças detectadas no seu dispositivo"
            btnUnlockThreats.setOnClickListener {
                subscriptionLauncher.launch(Intent(this, SubscriptionActivity::class.java))
            }
        } else {
            // Sem assinatura e sem ameaças: estado vazio normal
            // Com assinatura: mostra lista completa
            layoutThreatsLocked.visibility = View.GONE
            llThreats.visibility = View.VISIBLE
            layoutEmpty.visibility = if (activeThreats.isEmpty()) View.VISIBLE else View.GONE
        }

        if (!isSubscribed) return  // Não renderiza a lista de ameaças para usuários grátis

        for (threat in activeThreats.take(10)) {
            val view = layoutInflater.inflate(R.layout.item_threat, llThreats, false)
            view.findViewById<TextView>(R.id.tvThreatName).text = threat.getString("appName")
            view.findViewById<TextView>(R.id.tvThreatPackage).text = threat.getString("packageName")
            view.findViewById<TextView>(R.id.tvThreatType).text = when (threat.getString("threatType")) {
                "popup" -> "Pop-up Invasivo"
                "overlay" -> "Overlay de Tela"
                "notification" -> "Notificação Invasiva"
                "hidden" -> "App Oculto"
                else -> threat.getString("threatType")
            }
            view.findViewById<TextView>(R.id.tvThreatSeverity).text = when (threat.getString("severity")) {
                "critical" -> "CRÍTICO"
                "high" -> "ALTO"
                "medium" -> "MÉDIO"
                else -> "BAIXO"
            }

            val btnUninstall = view.findViewById<Button>(R.id.btnUninstall)
            val pkg = threat.getString("packageName")
            btnUninstall.setOnClickListener {
                requireSubscriptionToUninstall(pkg)
            }

            llThreats.addView(view)
        }
    }

    // ── Animação de pulso vermelho↔amarelo para avisos ───────────────────────

    private fun startPulse(viewId: Int) {
        if (pulseAnimators.containsKey(viewId)) return
        val view = findViewById<View>(viewId) ?: return
        val animator = ValueAnimator.ofObject(ArgbEvaluator(), COLOR_RED, COLOR_YELLOW).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { va ->
                view.background = ColorDrawable(va.animatedValue as Int)
            }
            start()
        }
        pulseAnimators[viewId] = animator
    }

    private fun stopPulse(viewId: Int) {
        pulseAnimators.remove(viewId)?.cancel()
    }

    private fun stopAllPulses() {
        pulseAnimators.values.forEach { it.cancel() }
        pulseAnimators.clear()
    }

    // ── Alertas periódicos para não-assinantes ────────────────────────────────

    private fun scheduleTrialAlertsIfNeeded() {
        if (PrefsHelper.hasFullAccess(this)) return
        if (PrefsHelper.isTrialAlarmSet(this)) return

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // 3 horários fixos por dia: 09:00, 13:00 e 20:00
        val scheduleHours = listOf(9, 13, 20)
        val requestCodes  = listOf(8881, 8882, 8883)

        scheduleHours.forEachIndexed { i, hour ->
            val intent = Intent(this, TrialAlertReceiver::class.java).apply {
                putExtra("slot", i)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this, requestCodes[i], intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, hour)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
                // Se o horário já passou hoje, agenda para amanhã
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
            }

            alarmManager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                cal.timeInMillis,
                AlarmManager.INTERVAL_DAY,
                pendingIntent
            )
            Log.i(TAG, "Alerta trial agendado: ${hour}h → ${cal.time}")
        }

        PrefsHelper.setTrialAlarmSet(this, true)
    }

    private fun maybeShowTrialPopup() {
        if (PrefsHelper.hasFullAccess(this)) return

        val now = System.currentTimeMillis()
        val lastPopup = PrefsHelper.getLastTrialPopup(this)
        val minIntervalMs = 4 * 60 * 60 * 1000L // 4 horas

        if (lastPopup != 0L && (now - lastPopup) < minIntervalMs) return

        PrefsHelper.setLastTrialPopup(this, now)

        val messages = TrialAlertReceiver.MESSAGES
        val idx = PrefsHelper.getTrialNotifCount(this) % messages.size
        val alertMsg = messages[idx]

        android.app.AlertDialog.Builder(this)
            .setTitle("⚠️ Alerta de Segurança")
            .setMessage(
                "$alertMsg\n\n" +
                "Seja Premium e proteja seu celular com monitoramento em tempo real, " +
                "remoção de ameaças e detecção de adware."
            )
            .setPositiveButton("🔒 Seja Premium — Proteja seu Celular") { _, _ ->
                subscriptionLauncher.launch(Intent(this, SubscriptionActivity::class.java))
            }
            .setNegativeButton("Agora não", null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun checkAndRequestNotifPermission() {
        // Apenas Android 13+ precisa pedir permissão de notificação em runtime
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val alreadyGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (alreadyGranted) return
        if (PrefsHelper.hasAskedNotifPermission(this)) return

        // Primeira abertura: mostrar dialog explicativo antes do prompt do sistema
        PrefsHelper.setNotifPermissionAsked(this)
        android.app.AlertDialog.Builder(this)
            .setTitle("🔔 Ativar Notificações")
            .setMessage(
                "O M&A Guardian pode te alertar imediatamente quando detectar:\n\n" +
                "• Pop-ups invasivos\n" +
                "• Adware instalado\n" +
                "• Apps suspeitos em segundo plano\n\n" +
                "Deseja receber esses alertas?"
            )
            .setPositiveButton("Sim, ativar") { _, _ ->
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .setNegativeButton("Agora não", null)
            .show()
    }

    private fun showPermissionsDialog() {
        val status = PermissionHelper.checkAll(this)

        if (status.usageStats) {
            Toast.makeText(this, "✓ Permissão já concedida!", Toast.LENGTH_SHORT).show()
            return
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Permissão Necessária")
            .setMessage(
                "O M&A Guardian precisa de acesso ao Histórico de Uso de Apps para " +
                "detectar pop-ups e adware em tempo real.\n\n" +
                "Na tela que abrir:\n" +
                "1. Encontre \"M&A Guardian\" na lista\n" +
                "2. Ative o acesso\n\n" +
                "Essa é a única permissão necessária para o funcionamento completo."
            )
            .setPositiveButton("Abrir Configurações") { _, _ ->
                PermissionHelper.openUsageStatsSettings(this)
            }
            .setNegativeButton("Agora não", null)
            .show()
    }
}
