package com.maguardian.security.ui

import android.app.usage.StorageStatsManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.maguardian.security.R
import com.maguardian.security.util.PermissionHelper
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Live system measurements; returning from Android Settings invalidates the old snapshot. */
class AppCacheActivity : AppCompatActivity() {
    private data class CacheEntry(val pkg: String, val label: String, val bytes: Long?)
    private val worker = Executors.newSingleThreadExecutor()
    private var pending: Future<*>? = null
    private var generation = 0
    private lateinit var rows: LinearLayout
    private lateinit var total: TextView
    private lateinit var summary: TextView
    private lateinit var progress: ProgressBar
    private lateinit var refresh: Button
    private lateinit var permission: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        GuardianAppearance.apply(this)
        super.onCreate(savedInstanceState)
        ScreenInsets.configure(window)
        setContentView(R.layout.activity_app_cache)
        ScreenInsets.apply(this, light = GuardianAppearance.isLight(this))
        GuardianNavigation.bind(this)
        rows = findViewById(R.id.llAppCaches)
        total = findViewById(R.id.tvAppCacheTotal)
        summary = findViewById(R.id.tvAppCacheSummary)
        progress = findViewById(R.id.progressAppCache)
        refresh = findViewById(R.id.btnCacheRefresh)
        permission = findViewById(R.id.btnCachePermission)
        findViewById<Button>(R.id.btnCacheBack).setOnClickListener { finish() }
        refresh.setOnClickListener { refreshMeasurements() }
        permission.setOnClickListener { openSystem(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        findViewById<Button>(R.id.btnCacheCleanAll).setOnClickListener {
            val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                StorageManager.ACTION_CLEAR_APP_CACHE else Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            openSystem(Intent(action), Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        if (GuardianAppearance.refreshIfChanged(this)) return
        refreshMeasurements()
    }

    override fun onDestroy() {
        generation++
        pending?.cancel(true)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun refreshMeasurements() {
        val request = ++generation
        pending?.cancel(true)
        rows.removeAllViews()
        total.text = "Consultando…"
        summary.text = "Lendo os tamanhos informados pelo Android."
        progress.visibility = View.VISIBLE
        refresh.isEnabled = false
        val allowed = PermissionHelper.hasUsageStatsPermission(this)
        permission.visibility = if (allowed) View.GONE else View.VISIBLE
        pending = worker.submit {
            try {
                val stats = getSystemService(STORAGE_STATS_SERVICE) as StorageStatsManager
                val installed = packageManager.getInstalledApplications(0)
                val entries = ArrayList<CacheEntry>()
                for (app in installed) {
                    if (Thread.currentThread().isInterrupted) return@submit
                    // Without usage access, only our own measurement is authorized.
                    if (!allowed && app.packageName != packageName) continue
                    val bytes = try {
                        stats.queryStatsForPackage(
                            StorageManager.UUID_DEFAULT, app.packageName, android.os.Process.myUserHandle()
                        ).cacheBytes.coerceAtLeast(0)
                    } catch (_: Exception) { null }
                    val label = try { packageManager.getApplicationLabel(app).toString() }
                        catch (_: Exception) { app.packageName }
                    entries.add(CacheEntry(app.packageName, label, bytes))
                }
                val sorted = entries.sortedWith(
                    compareByDescending<CacheEntry> { it.bytes ?: -1L }.thenBy { it.label.lowercase() }
                )
                runOnUiThread {
                    if (isFinishing || isDestroyed || request != generation) return@runOnUiThread
                    progress.visibility = View.GONE
                    refresh.isEnabled = true
                    val measured = sorted.filter { it.bytes != null }
                    total.text = if (measured.isEmpty()) "Indisponível"
                        else formatBytes(measured.sumOf { it.bytes!! })
                    val unavailable = sorted.size - measured.size
                    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
                    summary.text = if (!allowed) {
                        "Acesso de uso não autorizado. Somente o próprio app pôde ser consultado. Autorize para consultar os demais."
                    } else {
                        "${measured.size} apps consultados · atualizado às $time" +
                            if (unavailable > 0) "\n$unavailable apps sem tamanho disponível; não incluídos no total." else ""
                    }
                    if (sorted.isEmpty()) addMessage("Nenhum aplicativo disponível para consulta.")
                    sorted.forEach { addEntry(it) }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed || request != generation) return@runOnUiThread
                    progress.visibility = View.GONE
                    refresh.isEnabled = true
                    total.text = "Indisponível"
                    summary.text = "Não foi possível consultar o cache. Confira as permissões e tente atualizar novamente."
                }
            }
        }
    }

    private fun addMessage(message: String) {
        rows.addView(TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(GuardianAppearance.color(this@AppCacheActivity, R.color.guardian_muted))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        })
    }

    private fun addEntry(entry: CacheEntry) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@AppCacheActivity, R.drawable.guardian_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        labels.addView(TextView(this).apply {
            text = entry.label
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(GuardianAppearance.color(this@AppCacheActivity, R.color.guardian_ink))
        })
        labels.addView(TextView(this).apply {
            text = entry.bytes?.let(::formatBytes) ?: "Tamanho indisponível"
            textSize = 13f
            setPadding(0, dp(5), 0, 0)
            setTextColor(GuardianAppearance.color(this@AppCacheActivity, R.color.guardian_muted))
        })
        row.addView(labels)
        row.addView(androidx.appcompat.widget.AppCompatButton(this, null, 0).apply {
            text = "Abrir"
            textSize = 13f
            isAllCaps = false
            minHeight = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
            background = ContextCompat.getDrawable(this@AppCacheActivity, R.drawable.guardian_secondary)
            backgroundTintList = null
            setTextColor(GuardianAppearance.color(this@AppCacheActivity, R.color.guardian_ink))
            contentDescription = "Abrir configurações de ${entry.label}"
            setOnClickListener {
                openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${entry.pkg}")))
            }
        })
        rows.addView(row)
    }

    private fun openSystem(intent: Intent, fallback: Intent? = null) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            if (fallback != null) openSystem(fallback)
            else Toast.makeText(this, "Não foi possível abrir esta opção do Android.", Toast.LENGTH_LONG).show()
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes == 0L -> "0 MB"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
