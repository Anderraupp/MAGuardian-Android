package com.maguardian.security.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.maguardian.security.R
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
        checkPermissionsAndStart()
        checkAndRequestNotifPermission()
    }

    override fun onResume() {
        super.onResume()
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
    }

    private fun checkPermissionsAndStart() {
        val status = PermissionHelper.checkAll(this)
        if (status.allGranted) {
            if (PrefsHelper.isProtectionEnabled(this)) startDetectionService()
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

    private fun runManualScan() {
        val btnScan = findViewById<Button>(R.id.btnScan)
        btnScan.isEnabled = false
        btnScan.text = "Escaneando..."

        val installedPackages = packageManager.getInstalledPackages(
            android.content.pm.PackageManager.GET_PERMISSIONS
        )
        var threatsFound = 0

        // Whitelist ampla: apps legítimos conhecidos que usam overlay por razão válida
        // (chat heads, bolhas, avisos de bateria, acessibilidade, etc.)
        val overlayWhitelist = setOf(
            // Sistema Android
            "android", "com.android.systemui", "com.android.settings",
            "com.android.shell", "com.android.launcher", "com.android.launcher2",
            "com.android.launcher3", "com.android.packageinstaller",
            "com.android.permissioncontroller", "com.android.server.telecom",
            // Google
            "com.google.android.gms", "com.google.android.gsf",
            "com.google.android.vending", "com.android.vending",
            "com.google.android.apps.nexuslauncher", "com.google.android.inputmethod.latin",
            "com.google.android.apps.photos", "com.google.android.apps.messaging",
            "com.google.android.dialer", "com.google.android.contacts",
            "com.google.android.youtube", "com.google.android.googlequicksearchbox",
            "com.google.android.apps.maps", "com.google.android.apps.translate",
            "com.google.android.calendar", "com.google.android.keep",
            "com.google.android.apps.docs", "com.google.android.apps.drive",
            "com.google.android.talk", "com.google.android.apps.tachyon",
            // Samsung
            "com.sec.android.app.launcher", "com.samsung.android.honeyboard",
            "com.samsung.android.dialer", "com.samsung.android.app.notes",
            "com.samsung.android.messaging", "com.samsung.android.app.screenrecorder",
            "com.samsung.android.sidegesturepad", "com.samsung.android.app.tips",
            "com.samsung.android.app.assistantmenu",
            // Comunicação popular
            "com.whatsapp", "com.whatsapp.w4b",
            "org.telegram.messenger", "org.telegram.plus",
            "com.facebook.orca", "com.facebook.lite", "com.facebook.katana",
            "com.instagram.android", "com.twitter.android", "com.twitter.lite",
            "com.discord", "com.snapchat.android", "com.viber.voip",
            "com.skype.raider", "com.microsoft.teams", "com.slack",
            "com.linkedin.android", "com.pinterest",
            // Bancos e finanças brasileiros
            "com.itau", "com.itaucard", "br.com.itau.empresas",
            "com.bradesco", "br.com.bradesco.next",
            "com.nu.production", "com.nubank",
            "br.com.bb.android", "com.bb.mobile",
            "com.caixa.android", "com.cef.caixamobilebankingpf",
            "com.santander.app", "br.com.santander",
            "com.sicredi.app", "com.bancointer",
            "br.com.serasaexperian.consumidor", "br.com.serasa",
            "com.mercadopago.wallet", "com.mercadolibre",
            "com.picpay", "com.c6bank.app", "br.com.original",
            "br.com.realizecfi", "com.xp.carteira", "br.com.rico.app",
            "br.com.modalmais", "com.btgpactual",
            // Compras e e-commerce
            "com.alibaba.aliexpresshd", "com.alibaba.android.buyer",
            "com.amazon.mShop.android.shopping", "com.shopee.br",
            "com.magazineluiza.android", "br.com.americanas.android",
            "br.com.extra.android", "com.casasbahia.android",
            "com.magalu", "br.com.b2w.americanas",
            // Streaming e entretenimento
            "com.spotify.music", "com.netflix.mediaclient",
            "com.amazon.avod.thirdpartyclient", "com.disney.disneyplus",
            "br.com.globoplay.mobile", "com.hbomax.android",
            "com.crunchyroll.crunchyroid", "com.twitch.android.app",
            "com.tiktok", "com.zhiliaoapp.musically",
            // Delivery e transporte
            "com.ubercab", "com.ninety9.app", "br.com.ifood",
            "com.rappi.app", "br.com.loggi.android",
            "br.com.movile.pagseguro", "com.lalamove",
            // Ferramentas e produtividade
            "com.replit.app", "com.microsoft.office.outlook",
            "com.microsoft.office.word", "com.microsoft.office.excel",
            "com.adobe.reader", "com.dropbox.android",
            "com.evernote", "com.todoist.android",
            // Cripto e investimento
            "com.coinmarketcap.android", "com.binance.dev",
            "com.coinbase.android", "com.kucoin.exchange",
            "br.com.mercadobitcoin",
            // Saúde e outros
            "com.ubercab.eats", "com.swiftkey.swiftkeyapp",
            "com.touchtype.swiftkey", "com.grammarly.android",
            // Este app
            packageName
        )

        for (pkg in installedPackages) {
            val pkgName = pkg.packageName

            // 1. Verificação no banco de malware conhecido
            val malware = com.maguardian.security.data.MalwareDatabase.isMalware(pkgName)
            if (malware != null) {
                PrefsHelper.saveThreat(this, malware)
                threatsFound++
                Log.w(TAG, "Banco de malware: ${malware.packageName}")
                continue
            }

            // 2. Detecta apps com permissão de overlay que não são do sistema
            //    SYSTEM_ALERT_WINDOW é o que permite exibir pop-ups sobre outros apps
            val isSystemApp = (pkg.applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val requestsOverlay = pkg.requestedPermissions?.contains(
                android.Manifest.permission.SYSTEM_ALERT_WINDOW
            ) == true
            val isWhitelisted = overlayWhitelist.any { pkgName == it || pkgName.startsWith("$it.") }

            if (!isSystemApp && requestsOverlay && !isWhitelisted) {
                val appName = try {
                    packageManager.getApplicationLabel(pkg.applicationInfo).toString()
                } catch (e: Exception) { pkgName }

                val entry = com.maguardian.security.data.MalwareDatabase.MalwareEntry(
                    packageName = pkgName,
                    appName = appName,
                    threatType = "overlay",
                    severity = "medium",
                    description = "Solicita permissão de sobreposição de tela — usada para exibir pop-ups e anúncios sobre outros apps.",
                )
                PrefsHelper.saveThreat(this, entry)
                threatsFound++
                Log.w(TAG, "Overlay suspeito detectado: $pkgName ($appName)")
            }
        }

        PrefsHelper.setLastScan(this, System.currentTimeMillis())
        PrefsHelper.incrementScanCount(this)

        val msg = when {
            threatsFound > 0 -> "⚠️ $threatsFound ameaça(s) encontrada(s)! Veja abaixo."
            else -> "✓ Dispositivo limpo! Nenhuma ameaça detectada."
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

        btnScan.isEnabled = true
        btnScan.text = "Escanear"
        refreshUI()
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

        // Badge + lista de ameaças
        val threatCount = threats.length()
        tvThreatCount.text = "$threatCount registros"
        llThreats.removeAllViews()
        layoutEmpty.visibility = if (threatCount == 0) View.VISIBLE else View.GONE

        for (i in 0 until minOf(threatCount, 10)) {
            val threat = threats.getJSONObject(i)
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
                val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
                    data = android.net.Uri.parse("package:$pkg")
                }
                startActivity(uninstallIntent)
                PrefsHelper.markThreatRemoved(this, pkg)
                refreshUI()
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
