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

        val installedPackages = packageManager.getInstalledPackages(0)
        var threatsFound = 0

        for (pkg in installedPackages) {
            val malware = com.maguardian.security.data.MalwareDatabase.isMalware(pkg.packageName)
            if (malware != null) {
                PrefsHelper.saveThreat(this, malware)
                threatsFound++
                Log.w(TAG, "Ameaça encontrada: ${malware.packageName}")
            }
        }

        PrefsHelper.setLastScan(this, System.currentTimeMillis())
        PrefsHelper.incrementScanCount(this)

        Toast.makeText(
            this,
            if (threatsFound > 0) "$threatsFound ameaça(s) detectada(s)!" else "Dispositivo limpo!",
            Toast.LENGTH_LONG,
        ).show()

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
