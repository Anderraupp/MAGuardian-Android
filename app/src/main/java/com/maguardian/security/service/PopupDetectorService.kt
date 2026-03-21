package com.maguardian.security.service

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.maguardian.security.R
import com.maguardian.security.data.MalwareDatabase
import com.maguardian.security.ui.MainActivity
import com.maguardian.security.util.PrefsHelper

class PopupDetectorService : Service() {

    companion object {
        const val TAG = "PopupDetectorService"
        const val CHANNEL_ID_SERVICE = "maguardian_service"
        const val CHANNEL_ID_THREAT = "maguardian_threat"
        const val NOTIF_ID_SERVICE = 1001
        const val DETECTION_INTERVAL_MS = 1000L
        const val POPUP_THRESHOLD_MS = 1500L
        const val HEURISTIC_TRIGGER_COUNT = 3
        const val ACTION_STOP = "com.maguardian.security.ACTION_STOP"

        // Apps legítimas que podem aparecer rapidamente em foreground — não são ameaças
        val SYSTEM_WHITELIST = setOf(
            // ── Android puro ──
            "android",
            "com.android.systemui",
            "com.android.launcher",
            "com.android.launcher2",
            "com.android.launcher3",
            "com.android.phone",
            "com.android.incallui",
            "com.android.settings",
            "com.android.dialer",
            "com.android.contacts",
            "com.android.packageinstaller",
            "com.android.vending",
            "com.android.camera",
            "com.android.camera2",
            "com.android.gallery3d",
            "com.android.documentsui",
            "com.android.fileexplorer",
            "com.android.mms",
            "com.android.providers.downloads",
            "com.android.providers.downloads.ui",
            "com.android.bluetooth",
            "com.android.nfc",
            // ── Google ──
            "com.google.android.apps.nexuslauncher",
            "com.google.android.dialer",
            "com.google.android.apps.photos",
            "com.google.android.apps.messaging",
            "com.google.android.inputmethod.latin",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.packageinstaller",
            "com.google.android.youtube",
            "com.google.android.apps.docs",
            "com.google.android.apps.maps",
            "com.google.android.calendar",
            "com.google.android.contacts",
            "com.google.android.documentsui",
            "com.google.android.camera",
            // ── Samsung ──
            "com.sec.android.app.launcher",
            "com.samsung.android.dialer",
            "com.samsung.android.honeyboard",
            "com.samsung.android.app.contacts",
            "com.samsung.android.messaging",
            "com.samsung.android.gallery3d",
            "com.sec.android.app.myfiles",          // Meus Arquivos (gerenciador nativo Samsung)
            "com.samsung.android.myfiles",
            "com.sec.android.gallery3d",
            "com.sec.android.app.camera",
            "com.samsung.android.camera",
            "com.samsung.android.settings",
            "com.samsung.android.app.settings.bixby",
            "com.samsung.android.incallui",
            "com.samsung.android.app.spage",
            "com.samsung.android.app.clockpackage",
            "com.samsung.android.app.galaxyfinder",
            "com.samsung.android.sm",
            "com.samsung.android.sm.policy",
            "com.samsung.android.app.smartcapture",
            "com.samsung.android.themestore",
            "com.samsung.android.app.omcagent",
            "com.samsung.android.biometrics.app.setting",
            "com.samsung.android.app.taskedge",
            // ── Xiaomi / MIUI ──
            "com.miui.home",
            "com.miui.gallery",
            "com.miui.camera",
            "com.miui.filemanager",
            "com.miui.settings",
            "com.miui.securitycenter",
            "com.xiaomi.bluetooth",
            "com.miui.msa.global",
            "com.miui.systemAdSolution",
            // ── OnePlus / OxygenOS ──
            "com.oneplus.launcher",
            "com.oneplus.gallery",
            "com.oneplus.camera",
            "com.oneplus.filemanager",
            "net.oneplus.odm",
            // ── Oppo / ColorOS ──
            "com.oppo.launcher",
            "com.coloros.filemanager",
            "com.oppo.camera",
            // ── Vivo / FuntouchOS ──
            "com.vivo.launcher",
            "com.bbk.filemanager",
            "com.vivo.camera",
            // ── Huawei ──
            "com.huawei.android.launcher",
            "com.huawei.filemanager",
            "com.huawei.camera",
            "com.huawei.photos",
            // ── Motorola ──
            "com.motorola.launcher3",
            "com.motorola.camera2",
            "com.motorola.filemanager",
            // ── LG ──
            "com.lge.launcher3",
            "com.lge.filemanager",
            "com.lge.camera",
            // ── Teclados ──
            "com.swiftkey.swiftkeyapp",
            "com.touchtype.swiftkey",
            "com.gboard",
            "com.google.android.inputmethod.latin",
            // ── Apps populares legítimos ──
            "com.whatsapp",
            "com.facebook.orca",
            "com.instagram.android",
            "com.twitter.android",
            "com.spotify.music",
            "com.netflix.mediaclient",
            "com.amazon.mShop.android.shopping",
        )
    }

    private lateinit var usageStatsManager: UsageStatsManager
    private val handler = Handler(Looper.getMainLooper())
    private var lastForegroundPackage = ""
    private var lastForegroundTime = 0L
    private val notifiedPackages = mutableSetOf<String>()
    private val heuristicCounter = mutableMapOf<String, Int>()

    private val detectionRunnable = object : Runnable {
        override fun run() {
            detectPopup()
            handler.postDelayed(this, DETECTION_INTERVAL_MS)
        }
    }

    /**
     * Receptor DINÂMICO para PACKAGE_ADDED/PACKAGE_REPLACED.
     * Registrado enquanto o serviço está ativo — mais confiável que o receptor
     * estático em fabricantes como Xiaomi, Samsung e Huawei que bloqueiam
     * broadcasts de manifesto em segundo plano.
     */
    private val packageInstallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            if (action != Intent.ACTION_PACKAGE_ADDED && action != Intent.ACTION_PACKAGE_REPLACED) return

            val pkgName = intent.data?.schemeSpecificPart ?: return
            if (pkgName == packageName) return  // ignora atualização do próprio app
            val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)

            // Apps de sistema e vendors confiáveis nunca são ameaças
            if (MalwareDatabase.isSystemPrefix(pkgName) || MalwareDatabase.isTrustedApp(pkgName)) return

            Log.i(TAG, "📦 App instalado detectado pelo receptor dinâmico: $pkgName | substituindo=$isReplacing")

            Thread {
                // 1. Banco de malware exato
                val malware = MalwareDatabase.isMalware(pkgName)
                if (malware != null) {
                    Log.w(TAG, "🚨 MALWARE instalado: $pkgName")
                    notifiedPackages.add(pkgName)
                    PrefsHelper.saveThreat(this@PopupDetectorService, malware)
                    onThreatDetected(malware)
                    sendBroadcast(Intent("com.maguardian.security.THREAT_DETECTED"))
                    return@Thread
                }

                // 2. Heurística: nome + permissões
                val appLabel = try {
                    val info = packageManager.getApplicationInfo(pkgName, 0)
                    packageManager.getApplicationLabel(info).toString()
                } catch (e: Exception) { pkgName }

                val pkgInfo = try {
                    packageManager.getPackageInfo(pkgName, PackageManager.GET_PERMISSIONS)
                } catch (e: Exception) { null }

                val heuristic = MalwareDatabase.checkHeuristic(pkgName, appLabel, pkgInfo?.requestedPermissions)
                if (heuristic != null) {
                    Log.w(TAG, "⚠️ Suspeito heurístico instalado: $pkgName")
                    notifiedPackages.add("heuristic:$pkgName")
                    PrefsHelper.saveThreat(this@PopupDetectorService, heuristic)
                    onThreatDetected(heuristic)
                    sendBroadcast(Intent("com.maguardian.security.THREAT_DETECTED"))
                }
            }.start()
        }
    }

    override fun onCreate() {
        super.onCreate()
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        createNotificationChannels()

        // Reset automático de 12 horas — limpa contadores e estado interno do serviço
        val wasReset = PrefsHelper.maybeAutoReset(this)
        if (wasReset) {
            notifiedPackages.clear()
            heuristicCounter.clear()
            Log.i(TAG, "Reset automático de 12 horas executado — contadores e histórico limpos")
        }

        // Registra receptor dinâmico para capturar instalações enquanto serviço está ativo
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageInstallReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(packageInstallReceiver, filter)
        }
        Log.i(TAG, "Receptor dinâmico de instalação registrado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID_SERVICE, buildServiceNotification())
        handler.post(detectionRunnable)
        Log.i(TAG, "Serviço iniciado — monitorando eventos de uso")
        // Varredura dos apps já instalados em background
        Thread { runStartupScan() }.start()
        return START_STICKY
    }

    /**
     * Varre todos os apps instalados contra o banco de malware logo ao iniciar o serviço.
     * Roda em thread de background para não travar o serviço.
     */
    private fun runStartupScan() {
        Log.i(TAG, "Varredura inicial de apps instalados...")
        val installedPackages = try {
            packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao listar pacotes instalados", e)
            return
        }

        // Pré-computa packages com ícone no launcher em uma só query
        val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        val packagesWithLauncher = packageManager
            .queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.packageName }
            .toSet()

        var found = 0
        for (pkg in installedPackages) {
            val pkgName = pkg.packageName
            if (pkgName == packageName) continue
            // Já notificou antes nessa sessão — pula
            if (notifiedPackages.contains(pkgName)) continue
            // Apps de sistema e vendors confiáveis nunca são ameaças
            if (MalwareDatabase.isSystemPrefix(pkgName)) continue
            if (MalwareDatabase.isTrustedApp(pkgName)) continue

            val appLabel = try {
                packageManager.getApplicationLabel(pkg.applicationInfo).toString()
            } catch (e: Exception) { pkgName }

            val isSystemApp = (pkg.applicationInfo.flags and
                (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                 android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            val hasLauncherIcon = pkgName in packagesWithLauncher

            // 1. Banco de malware conhecido (detecção exata)
            val malware = MalwareDatabase.isMalware(pkgName)
            if (malware != null) {
                Log.w(TAG, "Varredura inicial — malware encontrado: $pkgName")
                notifiedPackages.add(pkgName)
                PrefsHelper.saveThreat(this, malware)
                onThreatDetected(malware)
                found++
                continue
            }

            // 2. Detecção de apps ocultos
            val hidden = MalwareDatabase.checkHiddenApp(
                pkgName, appLabel, pkg.requestedPermissions, hasLauncherIcon, isSystemApp
            )
            if (hidden != null) {
                Log.w(TAG, "Varredura inicial — app oculto: $pkgName")
                notifiedPackages.add("hidden:$pkgName")
                PrefsHelper.saveThreat(this, hidden)
                onThreatDetected(hidden)
                found++
                continue
            }

            // 3. Heurística: nome suspeito + permissões perigosas
            val heuristic = MalwareDatabase.checkHeuristic(
                pkgName, appLabel, pkg.requestedPermissions
            )
            if (heuristic != null) {
                Log.w(TAG, "Varredura inicial — suspeito heurístico: $pkgName")
                notifiedPackages.add("heuristic:$pkgName")
                PrefsHelper.saveThreat(this, heuristic)
                onThreatDetected(heuristic)
                found++
            }
        }

        PrefsHelper.setLastScan(this, System.currentTimeMillis())
        PrefsHelper.incrementScanCount(this)

        // Notifica a UI para atualizar a lista de ameaças
        sendBroadcast(Intent("com.maguardian.security.THREAT_DETECTED"))
        Log.i(TAG, "Varredura inicial concluída — $found ameaça(s) encontrada(s)")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(detectionRunnable)
        try {
            unregisterReceiver(packageInstallReceiver)
            Log.i(TAG, "Receptor dinâmico de instalação desregistrado")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receptor dinâmico já estava desregistrado")
        }
        Log.i(TAG, "Serviço encerrado")
    }

    private fun detectPopup() {
        val now = System.currentTimeMillis()
        val beginTime = now - DETECTION_INTERVAL_MS * 2

        val foregroundEventType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_FOREGROUND
        }

        try {
            val usageEvents = usageStatsManager.queryEvents(beginTime, now)

            while (usageEvents.hasNextEvent()) {
                val event = UsageEvents.Event()
                usageEvents.getNextEvent(event)

                if (event.eventType == foregroundEventType) {
                    val pkg = event.packageName
                    if (pkg == packageName) continue

                    val timeSinceLast = event.timeStamp - lastForegroundTime
                    Log.d(TAG, "Foreground: $pkg | tempo desde último: ${timeSinceLast}ms")

                    // ── 1. Verificação no banco de malware conhecido ──
                    val malware = MalwareDatabase.isMalware(pkg)
                    if (malware != null && !notifiedPackages.contains(pkg)) {
                        Log.w(TAG, "MALWARE CONHECIDO: $pkg")
                        notifiedPackages.add(pkg)
                        onThreatDetected(malware)
                    }

                    // ── 2. Detecção heurística: popup de app desconhecido ──
                    val isWhitelisted = SYSTEM_WHITELIST.any { wl ->
                        pkg == wl || pkg.startsWith("$wl.")
                    }
                    // Proteção: ignora fabricantes/sistema E apps financeiros/bancários
                    val isSystemPrefix = MalwareDatabase.isSystemPrefix(pkg)
                    val isTrustedApp  = MalwareDatabase.isTrustedApp(pkg)

                    if (malware == null &&
                        !isWhitelisted &&
                        !isSystemPrefix &&
                        !isTrustedApp &&
                        timeSinceLast in 80L until POPUP_THRESHOLD_MS &&
                        !notifiedPackages.contains("heuristic:$pkg")
                    ) {
                        val count = (heuristicCounter[pkg] ?: 0) + 1
                        heuristicCounter[pkg] = count
                        Log.d(TAG, "Heurística: $pkg apareceu rápido ($count vezes)")

                        if (count >= HEURISTIC_TRIGGER_COUNT) {
                            notifiedPackages.add("heuristic:$pkg")
                            onSuspiciousPopupDetected(pkg)
                        }
                    } else if (isWhitelisted || isSystemPrefix || isTrustedApp) {
                        // App seguro — zera qualquer contagem acumulada por engano
                        heuristicCounter.remove(pkg)
                    } else if (timeSinceLast >= POPUP_THRESHOLD_MS) {
                        // Resetar contador se o app ficou tempo normal em foreground
                        heuristicCounter.remove(pkg)
                    }

                    lastForegroundPackage = pkg
                    lastForegroundTime = event.timeStamp
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao consultar eventos de uso", e)
        }
    }

    private fun onSuspiciousPopupDetected(pkg: String) {
        val appName = try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            pkg
        }

        Log.w(TAG, "HEURÍSTICA: Pop-up suspeito detectado — $appName ($pkg)")

        val entry = MalwareDatabase.MalwareEntry(
            packageName = pkg,
            appName = appName,
            threatType = "popup",
            severity = "medium",
            description = "App exibiu comportamento de pop-up invasivo: apareceu em foreground repetidamente por menos de 1,5 segundo.",
        )
        onThreatDetected(entry)
    }

    private fun onThreatDetected(malware: MalwareDatabase.MalwareEntry) {
        PrefsHelper.saveThreat(this, malware)

        // Abre o diálogo de desinstalação diretamente — sem intermediário
        val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
            data = android.net.Uri.parse("package:${malware.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val uninstallPendingIntent = PendingIntent.getActivity(
            this,
            malware.packageName.hashCode(),
            uninstallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val severity = when (malware.severity) {
            "high" -> "ALTA"
            "medium" -> "MÉDIA"
            else -> "BAIXA"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_THREAT)
            .setSmallIcon(R.drawable.ic_shield_alert)
            .setContentTitle("⚠️ Ameaça Detectada — Severidade $severity")
            .setContentText("${malware.appName} está exibindo pop-ups invasivos")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("${malware.appName} (${malware.packageName})\n${malware.description}"),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .addAction(R.drawable.ic_delete, "Desinstalar", uninstallPendingIntent)
            .setColor(0xFFDC2626.toInt())
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(malware.packageName.hashCode(), notification)

        sendBroadcast(Intent("com.maguardian.security.THREAT_DETECTED").apply {
            putExtra("packageName", malware.packageName)
            putExtra("appName", malware.appName)
            putExtra("severity", malware.severity)
            putExtra("threatType", malware.threatType)
        })
    }

    private fun buildServiceNotification(): Notification {
        val stopIntent = Intent(this, PopupDetectorService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setSmallIcon(R.drawable.ic_shield_check)
            .setContentTitle("M&A Guardian — Proteção Ativa")
            .setContentText("Monitorando pop-ups e adware em tempo real")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPendingIntent)
            .addAction(0, "Parar", stopPendingIntent)
            .setColor(0xFFDC2626.toInt())
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            NotificationChannel(
                CHANNEL_ID_SERVICE,
                "Serviço de Proteção",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Monitoramento contínuo em segundo plano"
                setShowBadge(false)
                nm.createNotificationChannel(this)
            }

            NotificationChannel(
                CHANNEL_ID_THREAT,
                "Ameaças Detectadas",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alertas de pop-ups e adware detectados"
                enableVibration(true)
                nm.createNotificationChannel(this)
            }
        }
    }
}
