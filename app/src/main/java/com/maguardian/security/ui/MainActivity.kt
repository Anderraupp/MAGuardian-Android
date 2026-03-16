package com.maguardian.security.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.StorageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import com.maguardian.security.R
import com.maguardian.security.data.MalwareDatabase
import com.maguardian.security.service.PopupDetectorService
import com.maguardian.security.util.PermissionHelper
import com.maguardian.security.util.PrefsHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "MainActivity"
    }

    private val pendingUninstall = mutableSetOf<String>()

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
        setContentView(R.layout.activity_main)
        setupUI()
        checkPermissionsAndStart()       // mostra diálogo se precisar
        checkAndRequestNotifPermission()
    }

    override fun onResume() {
        super.onResume()
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
    }

    private fun setupUI() {
        val btnScan = findViewById<Button>(R.id.btnScan)
        val btnToggle = findViewById<TextView>(R.id.btnToggle)
        val btnClearThreats = findViewById<Button>(R.id.btnClearThreats)
        val btnPermissions = findViewById<Button>(R.id.btnPermissions)

        btnScan.setOnClickListener { runManualScan() }
        btnClearThreats.setOnClickListener {
            PrefsHelper.clearThreats(this)
            refreshUI()
        }
        btnPermissions.setOnClickListener { showPermissionsDialog() }
        btnToggle.setOnClickListener { toggleProtection() }

        val btnCleanCache = findViewById<Button>(R.id.btnCleanCache)
        btnCleanCache.setOnClickListener { runCacheClean() }

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
            // 1. Limpa cache do próprio app (sempre funciona)
            val ownCacheBefore = (cacheDir.walkTopDown().sumOf { it.length() })
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
            val ownFreed = ownCacheBefore

            // 2. Lê tamanho total atual para mostrar na UI
            val totalAfter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) calculateTotalCache() else 0L
            val browserAfter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) calculateBrowserCache() else 0L

            runOnUiThread {
                btnCleanCache.isEnabled = true
                btnCleanCache.text = "Limpar Cache"
                tvCacheSize.text = when {
                    totalAfter == 0L    -> "Toque para limpar o cache"
                    browserAfter > 0L   -> "${formatBytes(totalAfter)} em cache • Navegadores: ${formatBytes(browserAfter)}"
                    else                -> "${formatBytes(totalAfter)} em cache"
                }

                // Mostra resultado e pergunta se quer abrir configurações para limpar o restante
                val msg = if (ownFreed > 0)
                    "✓ ${formatBytes(ownFreed)} do app limpos."
                else
                    "Cache do app já estava limpo."

                if (totalAfter > 0L) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Cache Limpo")
                        .setMessage(
                            "$msg\n\n" +
                            "Ainda há ${formatBytes(totalAfter)} em cache de outros apps" +
                            (if (browserAfter > 0L) " (navegadores: ${formatBytes(browserAfter)})" else "") +
                            ".\n\nDeseja abrir as Configurações de Armazenamento para liberar mais espaço?"
                        )
                        .setPositiveButton("Abrir Configurações") { _, _ ->
                            try {
                                startActivity(Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                            } catch (e: Exception) {
                                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                            }
                        }
                        .setNegativeButton("OK", null)
                        .show()
                } else {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
                refreshCacheInfo()
            }
        }.start()
    }

    /**
     * Chamado em onResume(): inicia serviço + scan automático se permissões foram
     * recém-concedidas, sem exibir nenhum diálogo. Cobre o caso em que o usuário
     * saiu para Configurações, concedeu UsageStats e voltou ao app.
     */
    private fun maybeStartServiceSilently() {
        val status = PermissionHelper.checkAll(this)
        if (!status.allGranted) return
        if (PrefsHelper.isProtectionEnabled(this)) startDetectionService()
        // Primeira varredura automática (device já tinha apps antes de instalar o app)
        if (PrefsHelper.getLastScan(this) == 0L && !isScanning) {
            runManualScan()
        }
    }

    private fun checkPermissionsAndStart() {
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

    private fun runManualScan() {
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

            var threatsFound = 0

            for (pkg in installedPackages) {
                val pkgName = pkg.packageName

                // 1. Verifica no banco de dados (detecção exata)
                val malware = MalwareDatabase.isMalware(pkgName)
                if (malware != null) {
                    PrefsHelper.saveThreat(this, malware)
                    sendScanThreatNotification(malware)
                    threatsFound++
                    Log.w(TAG, "Ameaça (banco): $pkgName")
                    continue
                }

                // 2. Heurística: analisa nome + permissões para apps não catalogados
                val appLabel = try {
                    packageManager.getApplicationLabel(pkg.applicationInfo).toString()
                } catch (e: Exception) { pkgName }

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

            val msg = when {
                threatsFound > 0 -> "⚠️ $threatsFound ameaça(s) encontrada(s)! Veja abaixo."
                else -> "✓ Dispositivo limpo! Nenhuma ameaça detectada."
            }

            runOnUiThread {
                isScanning = false
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                btnScan.isEnabled = true
                btnScan.text = "Escanear"
                refreshUI()
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

        val severity = when (malware.severity) {
            "high" -> "ALTA"
            "medium" -> "MÉDIA"
            else -> "BAIXA"
        }

        val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:${malware.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val uninstallPi = PendingIntent.getActivity(
            this, malware.packageName.hashCode(), uninstallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, PopupDetectorService.CHANNEL_ID_THREAT)
            .setSmallIcon(R.drawable.ic_shield_alert)
            .setContentTitle("⚠️ Ameaça Detectada — Severidade $severity")
            .setContentText("${malware.appName} está instalado no dispositivo")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        "${malware.appName} (${malware.packageName})\n" +
                        "${malware.description}"
                    )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .addAction(R.drawable.ic_delete, "Desinstalar", uninstallPi)
            .setColor(0xFFDC2626.toInt())
            .build()

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
        val cardStatus = findViewById<LinearLayout>(R.id.cardStatus)
        val ivShield = findViewById<android.widget.ImageView>(R.id.ivShield)
        val layoutEmpty = findViewById<LinearLayout>(R.id.layoutEmpty)

        // Aviso de permissões
        if (!permStatus.allGranted) {
            layoutPermWarning.visibility = View.VISIBLE
            tvPermWarning.text = "Permissão necessária: Acesso ao Histórico de Uso de Apps"
        } else {
            layoutPermWarning.visibility = View.GONE
        }

        // Taxa de segurança
        val securityPct = if (totalScans == 0) 100
                          else ((totalScans - totalFound).coerceAtLeast(0) * 100 / totalScans)
        tvSecurityRate.text = "$securityPct%"
        tvSecurityRate.setTextColor(
            ContextCompat.getColor(this,
                if (securityPct >= 80) R.color.success else R.color.danger)
        )
        tvScansClean.text = "${(totalScans - totalFound).coerceAtLeast(0)}/$totalScans scans limpos"

        // Status card
        val isFullyActive = protectionEnabled && permStatus.allGranted
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
            tvStatus.text = if (!permStatus.allGranted) "Permissões Necessárias" else "Proteção Inativa"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            tvStatusBadge.text = "Risco"
            tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.danger))
            tvStatusBadge.background = ContextCompat.getDrawable(this, R.drawable.badge_danger)
            tvStatusDesc.text = if (!permStatus.allGranted)
                "Conceda as permissões para ativar a proteção."
            else
                "Ative a proteção para monitorar ameaças automaticamente."
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
        layoutEmpty.visibility = if (activeThreats.isEmpty()) View.VISIBLE else View.GONE

        for (threat in activeThreats.take(10)) {
            val view = layoutInflater.inflate(R.layout.item_threat, llThreats, false)
            view.findViewById<TextView>(R.id.tvThreatName).text = threat.getString("appName")
            view.findViewById<TextView>(R.id.tvThreatPackage).text = threat.getString("packageName")
            view.findViewById<TextView>(R.id.tvThreatType).text = when (threat.getString("threatType")) {
                "popup" -> "Pop-up Invasivo"
                "overlay" -> "Overlay de Tela"
                "notification" -> "Notificação Invasiva"
                else -> threat.getString("threatType")
            }
            view.findViewById<TextView>(R.id.tvThreatSeverity).text = when (threat.getString("severity")) {
                "high" -> "ALTO"
                "medium" -> "MÉDIO"
                else -> "BAIXO"
            }

            val btnUninstall = view.findViewById<Button>(R.id.btnUninstall)
            val pkg = threat.getString("packageName")
            btnUninstall.setOnClickListener {
                // Adiciona à lista de pendentes — remoção confirmada no onResume()
                pendingUninstall.add(pkg)
                val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
                    data = android.net.Uri.parse("package:$pkg")
                }
                startActivity(uninstallIntent)
            }

            llThreats.addView(view)
        }
    }

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
