package com.maguardian.security.ui

import android.app.usage.StorageStatsManager
import android.content.Intent
import android.net.Uri
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Live system measurements; returning from Android Settings invalidates the old snapshot. */
class AppCacheActivity : AppCompatActivity() {
    private data class CacheEntry(val pkg: String, val label: String, val bytes: Long?)
    private val worker = Executors.newSingleThreadExecutor()
    private val megabytes = java.text.DecimalFormat("#,##0.0",
        java.text.DecimalFormatSymbols(java.util.Locale.ROOT).apply {
            decimalSeparator = ','
            groupingSeparator = '.'
            zeroDigit = '0'
        }).apply { roundingMode = java.math.RoundingMode.HALF_UP }
    private var pending: Future<*>? = null
    private var generation = 0
    private lateinit var rows: LinearLayout
    private lateinit var total: TextView
    private lateinit var summary: TextView
    private lateinit var progress: ProgressBar
    private lateinit var refresh: Button

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
        centerButtonIcon(refresh, R.drawable.guardian_ic_refresh)
        val listScroll = findViewById<androidx.core.widget.NestedScrollView>(R.id.cacheListScroll)
        val hint = findViewById<View>(R.id.cacheListScrollHint)
        fun updateScrollHint() {
            hint.visibility = if (listScroll.canScrollVertically(1)) View.VISIBLE else View.INVISIBLE
        }
        rows.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateScrollHint() }
        listScroll.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener {
                _, _, _, _, _ -> updateScrollHint()
        })
        findViewById<Button>(R.id.btnCacheBack).setOnClickListener { finish() }
        refresh.setOnClickListener { refreshMeasurements() }
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
        pending = worker.submit {
            try {
                val stats = getSystemService(STORAGE_STATS_SERVICE) as StorageStatsManager
                // This is a user-facing cache list, not the threat scanner.
                // Android also lists providers, overlays and internal services
                // as installed packages; only show apps with a launcher icon here.
                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val launchablePackages = packageManager.queryIntentActivities(launcherIntent, 0)
                    .mapNotNull { it.activityInfo?.packageName }.toSet()
                val installed = packageManager.getInstalledApplications(0)
                    .filter { it.enabled && it.packageName in launchablePackages }
                    .distinctBy { it.packageName }
                val entries = ArrayList<CacheEntry>()
                for (app in installed) {
                    if (Thread.currentThread().isInterrupted) return@submit
                    // Listing an app does not require access to its storage statistics.
                    val bytes = if (!allowed && app.packageName != packageName) null else try {
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
                    val time = SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR")).format(Date())
                    summary.text = if (!allowed) {
                        "${sorted.size} aplicativos · atualizado às $time\nAcesso de uso não autorizado. Autorize para medir o cache dos demais aplicativos. Tamanhos indisponíveis não entram no total."
                    } else {
                        "${sorted.size} ${if (sorted.size == 1) "aplicativo" else "aplicativos"} · atualizado às $time" +
                            if (unavailable > 0) "\n$unavailable ${if (unavailable == 1) "aplicativo sem tamanho disponível; não incluído" else "aplicativos sem tamanho disponível; não incluídos"} no total." else ""
                    }
                    if (sorted.isEmpty()) addMessage("Nenhum aplicativo disponível para consulta.")
                    renderEntries(sorted)
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
        addEntryTo(entry, rows, false)
    }

    private fun renderEntries(entries: List<CacheEntry>) {
        val twoColumns = resources.configuration.screenWidthDp >= 360 && resources.configuration.fontScale <= 1.15f
        if (!twoColumns) { entries.forEach { addEntry(it) }; return }
        entries.chunked(2).forEach { pair ->
            val line = object : LinearLayout(this) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                    // Measure natural heights first, then align both real cards.
                    // MATCH_PARENT here collapses an odd final card in a ScrollView.
                    for (i in 0 until childCount) {
                        val child = getChildAt(i)
                        if (child !is LinearLayout) continue
                        val margins = child.layoutParams as LinearLayout.LayoutParams
                        val available = measuredHeight - paddingTop - paddingBottom - margins.topMargin - margins.bottomMargin
                        if (available > 0) child.measure(
                            View.MeasureSpec.makeMeasureSpec(child.measuredWidth, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.EXACTLY))
                    }
                }
            }.apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }
            pair.forEach { entry -> addEntryTo(entry, line, true) }
            if (pair.size == 1) line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            rows.addView(line)
        }
    }

    private fun addEntryTo(entry: CacheEntry, parent: LinearLayout, compact: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@AppCacheActivity, R.drawable.guardian_compact_card)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(if (compact) 0 else -1, -2, if (compact) 1f else 0f).apply {
                bottomMargin = dp(6)
                if (compact) { marginStart = dp(3); marginEnd = dp(3) }
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(32)
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        header.addView(android.widget.ImageView(this).apply {
            setImageDrawable((try { packageManager.getApplicationIcon(entry.pkg) }
                catch (_: Exception) { null })
                ?: ContextCompat.getDrawable(this@AppCacheActivity, R.drawable.guardian_ic_app))
            contentDescription = null
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(6) }
        })
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        labels.addView(TextView(this).apply {
            text = entry.label
            textSize = 13f
            includeFontPadding = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(GuardianAppearance.color(this@AppCacheActivity, R.color.guardian_ink))
        })
        labels.addView(TextView(this).apply {
            text = entry.bytes?.let(::formatBytes) ?: "Tamanho indisponível"
            textSize = 12f
            includeFontPadding = false
            setPadding(0, dp(2), 0, 0)
            setTextColor(GuardianAppearance.color(this@AppCacheActivity, R.color.guardian_muted))
        })
        header.addView(labels)
        row.addView(header)
        row.addView(CenteredIconButton(this).apply {
            text = "Abrir"
            textSize = 13f
            isAllCaps = false
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(-1, dp(48))
            minWidth = dp(76)
            minHeight = dp(48)
            setPadding(dp(10), 0, dp(10), 0)
            background = android.graphics.drawable.InsetDrawable(
                ContextCompat.getDrawable(this@AppCacheActivity, R.drawable.guardian_compact_card), 0, dp(6), 0, dp(6))
            backgroundTintList = null
            setTextColor(GuardianAppearance.color(this@AppCacheActivity, R.color.guardian_ink))
            contentDescription = "Abrir configurações de ${entry.label}"
            setOnClickListener {
                openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${entry.pkg}")))
            }
        }.also { centerButtonIcon(it, R.drawable.guardian_ic_external) })
        parent.addView(row)
    }

    /** Center the icon and label as a group without replacing the actual button. */
    private fun centerButtonIcon(button: Button, drawable: Int) {
        button.setCompoundDrawablesWithIntrinsicBounds(drawable, 0, 0, 0)
        button.compoundDrawablePadding = dp(6)
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
        else -> "${megabytes.format(bytes / (1024.0 * 1024))} MB"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
