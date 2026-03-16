package com.maguardian.security.service

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
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
            "android",
            "com.android.systemui",
            "com.android.launcher",
            "com.android.launcher2",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.huawei.android.launcher",
            "com.miui.home",
            "com.oneplus.launcher",
            "com.oppo.launcher",
            "com.vivo.launcher",
            "com.motorola.launcher3",
            "com.android.phone",
            "com.android.incallui",
            "com.android.settings",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.android.contacts",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.swiftkey.swiftkeyapp",
            "com.touchtype.swiftkey",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.android.vending",
            "com.google.android.apps.photos",
            "com.google.android.apps.messaging",
            "com.whatsapp",
            "com.facebook.orca",
            "com.instagram.android",
            "com.twitter.android",
            "com.spotify.music",
            "com.google.android.youtube",
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

    override fun onCreate() {
        super.onCreate()
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID_SERVICE, buildServiceNotification())
        handler.post(detectionRunnable)
        Log.i(TAG, "Serviço iniciado — monitorando eventos de uso")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(detectionRunnable)
        Log.i(TAG, "Serviço encerrado")
    }

    private fun detectPopup() {
        val now = System.currentTimeMillis()
        val beginTime = now - DETECTION_INTERVAL_MS * 2

        try {
            val usageEvents = usageStatsManager.queryEvents(beginTime, now)

            while (usageEvents.hasNextEvent()) {
                val event = UsageEvents.Event()
                usageEvents.getNextEvent(event)

                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
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

                    if (malware == null &&
                        !isWhitelisted &&
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
                    } else if (!isWhitelisted && timeSinceLast >= POPUP_THRESHOLD_MS) {
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
