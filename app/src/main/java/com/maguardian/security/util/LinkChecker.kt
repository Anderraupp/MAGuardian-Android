package com.maguardian.security.util

object LinkChecker {

    data class Result(
        val score: Int,
        val verdict: String,
        val reasons: List<String>
    )

    private val phishingKeywords = listOf(
        "itau", "nubank", "bradesco", "caixa", "santander", "banco", "mercado-pago",
        "mercadopago", "checkout", "pagamento", "recadastro", "atualizar", "seguranca",
        "token", "promocao", "fgts", "premio", "vaga", "sorteio", "ganhe", "pix",
        "bloqueada", "urgente", "cpf", "senha", "confirme", "acesso", "limite",
        "desbloqueio", "faturamail", "bb", "sicoob", "sicredi", "inter", "c6bank"
    )

    private val suspiciousTLDs = listOf(
        ".xyz", ".top", ".click", ".online", ".site", ".ru", ".cc", ".icu",
        ".club", ".link", ".tk", ".cf", ".gq", ".ml", ".ga", ".info", ".biz"
    )

    fun check(url: String): Result {
        val lower = url.lowercase().trim()
        val reasons = mutableListOf<String>()
        var score = 0

        val matched = phishingKeywords.filter { lower.contains(it) }
        if (matched.isNotEmpty()) {
            score += (matched.size * 20).coerceAtMost(80)
            reasons.add("Palavras de golpe financeiro: ${matched.take(4).joinToString(", ")}")
        }

        val tld = suspiciousTLDs.firstOrNull { lower.contains(it) }
        if (tld != null) {
            score += 30
            reasons.add("Domínio suspeito ($tld) — comum em phishing temporário")
        }

        if (Regex("""^(https?://)?(\d{1,3}\.){3}\d{1,3}""").containsMatchIn(lower)) {
            score += 40
            reasons.add("Acesso por IP direto — servidores legítimos usam domínio")
        }

        if (lower.startsWith("http://") && !lower.startsWith("https://")) {
            score += 15
            reasons.add("Conexão sem criptografia (HTTP) — sites sérios usam HTTPS")
        }

        val hyphens = lower.count { it == '-' }
        if (hyphens >= 3) {
            score += 20
            reasons.add("Excesso de hifens ($hyphens) para camuflar domínio oficial")
        }

        val subdomains = lower.removePrefix("https://").removePrefix("http://")
            .substringBefore("/").split(".").size
        if (subdomains >= 5) {
            score += 15
            reasons.add("Muitos subdomínios ($subdomains) para disfarçar URL real")
        }

        val finalScore = score.coerceAtMost(100)
        val verdict = when {
            finalScore >= 55 -> "FRAUDULENTO"
            finalScore >= 20 -> "SUSPEITO"
            else -> "SEGURO"
        }

        return Result(finalScore, verdict, reasons)
    }
}
