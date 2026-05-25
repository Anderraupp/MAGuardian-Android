package com.maguardian.security.util

object PhoneAnalyzer {

    data class Result(
        val score: Int,
        val label: String,
        val emoji: String,
        val reasons: List<String>
    )

    fun analyze(rawNumber: String): Result {
        val raw = rawNumber.replace("[^+0-9]".toRegex(), "")

        // Normaliza E.164 brasileiro (+55XX... ou 55XX...) para formato local (0XX...)
        // O Android passa o número ao CallScreeningService em formato +55XXXXXXXXXXX;
        // sem normalização, os checks de comprimento falhavam.
        val number = when {
            raw.startsWith("+55") && raw.length > 3 -> "0${raw.substring(3)}"
            raw.startsWith("55") && !raw.startsWith("+") && raw.length >= 12 -> "0${raw.substring(2)}"
            else -> raw
        }

        val reasons = mutableListOf<String>()
        var score = 0

        // Unknown / private number
        if (number.isBlank() || number == "unknown" || number == "private") {
            reasons.add("Número oculto ou privado — prática comum em golpes")
            score += 35
        }

        // International (not Brazil +55) — common scam origin
        if (number.startsWith("+") && !number.startsWith("+55")) {
            reasons.add("Ligação de país estrangeiro (não-Brasil)")
            score += 30
        }

        // 0800 — often spoofed to impersonate banks
        if (number.startsWith("0800") || number.startsWith("08007") || number.startsWith("08006")) {
            reasons.add("Número 0800 — frequentemente falsificados para imitar bancos")
            score += 20
        }

        // Very short number (spoofed display)
        if (number.length in 3..5 && number.isNotBlank()) {
            reasons.add("Número muito curto (${number.length} dígitos) — possível identificador falso")
            score += 25
        }

        // Repeated digits (e.g. 55555555555) — synthetic/spoofed
        val digits = number.filter { it.isDigit() }
        if (digits.length >= 8) {
            val unique = digits.toSet().size
            if (unique <= 2) {
                reasons.add("Número com dígitos muito repetidos — padrão de número sintético")
                score += 30
            }
        }

        // ── Prefixo 0303 — ANATEL obriga TODAS as empresas de telemarketing a usar ──
        // Qualquer ligação começando com 0303 É telemarketing por lei (Resolução ANATEL 632).
        // Não é suspeito — é CERTEZA. Score direto 65 para sempre superar o limiar de bloqueio.
        if (number.startsWith("0303")) {
            reasons.add("Prefixo 0303 — ANATEL exige este número para TODA empresa de telemarketing no Brasil")
            score += 65
        }

        // ── Outros prefixos de serviços suspeitos ──────────────────────────────
        // 0508: prefixo de recuperação de crédito / cobrança (regulamentado ANATEL)
        // 4004/4003/4001: SAC/call-center de bancos — legítimos mas frequentemente falsificados
        val serviceScamPrefixes = listOf("0508", "0190", "0191", "0192", "0500")
        val matchedPrefix = serviceScamPrefixes.firstOrNull { number.startsWith(it) }
        if (matchedPrefix != null) {
            reasons.add("Prefixo $matchedPrefix usado em cobrança/serviços de call-center")
            score += 30
        }

        // ── 4003/4004/4001 — curtos, usados por bancos mas muito falsificados ──
        val shortServicePrefixes = listOf("40041", "40031", "40011", "30031", "30041")
        if (shortServicePrefixes.any { number.startsWith(it) }) {
            reasons.add("Número de SAC bancário (4003/4004) — frequentemente falsificados em golpes de voz")
            score += 25
        }

        // Número com comprimento fora do padrão brasileiro
        // BR: 0 + 2 DDD + 9 (móvel) = 12 dígitos máx; sem 0: 11 dígitos
        val startsWithZero = digits.startsWith("0") && !digits.startsWith("0800")
        if (startsWithZero && digits.length >= 13) {
            reasons.add("Número longo fora do padrão BR (${digits.length} dígitos com 0) — possível VOIP ou falsificado")
            score += 25
        } else if (digits.length > 13) {
            reasons.add("Número com tamanho incomum (${digits.length} dígitos)")
            score += 20
        }

        // DDD inexistente no Brasil (ex: 04x, 09x — não são DDDs reais)
        val ddd = when {
            startsWithZero && digits.length >= 4 -> digits.substring(1, 3) // remove o 0 inicial
            !digits.startsWith("+") && digits.length >= 3 -> digits.substring(0, 2)
            else -> ""
        }
        val validDDDs = setOf(
            "11","12","13","14","15","16","17","18","19",
            "21","22","24","27","28",
            "31","32","33","34","35","37","38",
            "41","42","43","44","45","46","47","48","49",
            "51","53","54","55",
            "61","62","63","64","65","66","67","68","69",
            "71","73","74","75","77","79",
            "81","82","83","84","85","86","87","88","89",
            "91","92","93","94","95","96","97","98","99"
        )
        if (ddd.isNotEmpty() && ddd.all { it.isDigit() } && ddd !in validDDDs) {
            reasons.add("DDD inválido no Brasil ($ddd) — número falsificado ou VOIP")
            score += 30
        }

        val finalScore = score.coerceAtMost(100)
        val (label, emoji) = when {
            finalScore >= 70 -> "Possível Golpe"         to "🚨"
            finalScore >= 45 -> "Número Muito Suspeito"  to "🔴"
            finalScore >= 25 -> "Telemarketing / Cobrança" to "⚠️"
            else             -> "Ligação Segura"          to "✅"
        }

        return Result(finalScore, label, emoji, reasons)
    }
}
