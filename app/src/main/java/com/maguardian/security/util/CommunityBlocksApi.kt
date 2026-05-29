package com.maguardian.security.util

import android.content.Context
import android.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Cliente HTTP leve para a API de bloqueios colaborativos do M&A Guardian.
 * Usa HttpURLConnection padrão — sem dependências extras.
 * Todas as chamadas são assíncronas (fire-and-forget ou com callback).
 */
object CommunityBlocksApi {

    private val executor = Executors.newSingleThreadExecutor()
    private const val TAG = "CommunityBlocksApi"
    private const val TIMEOUT_MS = 10_000

    /**
     * Reporta um número bloqueado manualmente para o servidor comunitário.
     * Fire-and-forget: falhas de rede são silenciosas (não impactam o usuário).
     */
    fun reportBlock(number: String) {
        if (number.isBlank()) return
        executor.execute {
            try {
                val url = URL("${AppConfig.API_BASE_URL}/api/community-blocks")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    doOutput = true
                }
                OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                    writer.write("{\"number\":\"${number.trim()}\"}")
                    writer.flush()
                }
                val code = conn.responseCode
                Log.d(TAG, "reportBlock($number) → HTTP $code")
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "reportBlock falhou (offline?): ${e.message}")
            }
        }
    }

    /**
     * Baixa a lista comunitária de números bloqueados e salva localmente.
     * Chame periodicamente (ex: ao iniciar o serviço, 1x por hora).
     * [onDone] é chamado na thread de trabalho após a sincronização.
     */
    fun syncToLocal(context: Context, onDone: ((count: Int) -> Unit)? = null) {
        executor.execute {
            try {
                val url = URL("${AppConfig.API_BASE_URL}/api/community-blocks?min=${AppConfig.COMMUNITY_BLOCK_MIN_REPORTS}")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                }
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    return@execute
                }
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                conn.disconnect()

                val numbers = parseNumbersArray(body)
                if (numbers.isNotEmpty()) {
                    PrefsHelper.saveCommunityBlocks(context, numbers.toSet())
                    Log.d(TAG, "syncToLocal: ${numbers.size} números comunitários salvos")
                }
                onDone?.invoke(numbers.size)
            } catch (e: Exception) {
                Log.w(TAG, "syncToLocal falhou (offline?): ${e.message}")
                onDone?.invoke(0)
            }
        }
    }

    /**
     * Parser JSON mínimo para extrair o array "numbers" sem depender de biblioteca.
     * Resposta esperada: { "numbers": ["45999...", "11998...", ...], "total": N }
     */
    private fun parseNumbersArray(json: String): List<String> {
        val result = mutableListOf<String>()
        val match = Regex("\"numbers\"\\s*:\\s*\\[([^\\]]*)]").find(json) ?: return result
        val inner = match.groupValues[1]
        val items = Regex("\"([0-9]+)\"").findAll(inner)
        items.forEach { result.add(it.groupValues[1]) }
        return result
    }
}
