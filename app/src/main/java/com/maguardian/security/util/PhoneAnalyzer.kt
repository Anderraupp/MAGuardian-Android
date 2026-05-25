package com.maguardian.security.util

object PhoneAnalyzer {

    data class Result(
        val score: Int,
        val label: String,
        val emoji: String,
        val reasons: List<String>
    )

    fun analyze(rawNumber: String): Result {
        val number = rawNumber.replace("[^+0-9]".toRegex(), "")
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

        // Brazilian toll-free patterns used in scams
        val scamPrefixes = listOf("0303", "0508", "0190", "0191", "0192")
        val matchedPrefix = scamPrefixes.firstOrNull { number.startsWith(it) }
        if (matchedPrefix != null) {
            reasons.add("Prefixo $matchedPrefix associado a telemarketing agressivo/golpe")
            score += 25
        }

        // Very long number (spoofed international)
        if (digits.length > 13) {
            reasons.add("Número com tamanho incomum (${digits.length} dígitos)")
            score += 15
        }

        val finalScore = score.coerceAtMost(100)
        val (label, emoji) = when {
            finalScore >= 55 -> "Possível Golpe" to "🚨"
            finalScore >= 25 -> "Número Suspeito" to "⚠️"
            else             -> "Ligação Segura"  to "✅"
        }

        return Result(finalScore, label, emoji, reasons)
    }
}
