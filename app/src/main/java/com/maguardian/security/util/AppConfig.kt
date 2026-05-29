package com.maguardian.security.util

/**
 * Configurações globais do M&A Guardian.
 * Atualize API_BASE_URL com a URL do seu app Replit publicado (sem barra final).
 * Exemplo: "https://antivirus-ma.replit.app"
 */
object AppConfig {

    /**
     * URL base da API colaborativa.
     * Troque pela URL do seu deploy no Replit após publicar.
     */
    const val API_BASE_URL = "https://antivirus-ma.replit.app"

    /** Mínimo de denúncias para um número entrar na lista comunitária local. */
    const val COMMUNITY_BLOCK_MIN_REPORTS = 2

    /** Intervalo de sincronização da lista comunitária (6 horas em ms). */
    const val COMMUNITY_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L
}
