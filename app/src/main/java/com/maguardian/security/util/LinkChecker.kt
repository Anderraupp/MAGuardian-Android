package com.maguardian.security.util

object LinkChecker {

    data class Result(
        val score: Int,
        val verdict: String,
        val reasons: List<String>
    )

    // ── 1. Golpes financeiros brasileiros ────────────────────────────────────
    private val financialKeywords = listOf(
        "itau", "nubank", "bradesco", "caixa", "santander", "banco", "mercado-pago",
        "mercadopago", "checkout", "pagamento", "recadastro", "atualizar", "seguranca",
        "token", "promocao", "fgts", "premio", "vaga", "sorteio", "ganhe", "pix",
        "bloqueada", "urgente", "cpf", "senha", "confirme", "acesso", "limite",
        "desbloqueio", "faturamail", "bb", "sicoob", "sicredi", "inter", "c6bank",
        "picpay", "next", "will", "original", "pagseguro", "cielo", "credito",
        "saldo", "debito", "emprestimo", "consignado", "inss", "previdencia"
    )

    // ── 2. APK falso / pirataria / malware ──────────────────────────────────
    private val apkPiracyKeywords = listOf(
        "modapk", "apkmod", "mod-apk", "apk-mod", "moddedapk", "apkpremium",
        "latestmod", "modapks", "apklatest", "crackedapk", "apkhack",
        "apkpure-", "happymod", "apktool", "apkdownload", "freeapk",
        "modded", "cracked", "unlimited-", "unlimitedgems", "unlimitedcoins",
        "hackgame", "cheatgame", "aimbotapk", "wallhackapk"
    )

    // Detecta domínio que tem "apk" E "mod" em qualquer posição
    private val apkSignalWords = listOf("apk", "mod", "crack", "hack", "cheat", "warez", "keygen", "patch")

    // ── 3. Iscas populares (jogos / apps que nunca liberam APK oficial fora da loja) ──
    private val baitNames = listOf(
        "gta-5", "gta-6", "gta5", "gta6", "gta-vi", "gtavi",
        "freefire-", "freefiremax", "pubg-", "fortnite-", "valorant-",
        "minecraft-", "roblox-", "among-us", "callofduty-",
        "netflix-", "spotifypremium", "spotify-premium", "amazonprime",
        "disneyplus", "hbomax-", "primevideo", "playstationplus",
        "whatsappgold", "whatsapp-gold", "whatsapp-plus", "whatsappplus",
        "instagrampro", "tiktokpro", "youtubepremium-", "youtube-vanced"
    )

    // ── 4. TLDs de alto risco ────────────────────────────────────────────────
    private val suspiciousTLDs = listOf(
        ".xyz", ".top", ".click", ".online", ".site", ".ru", ".cc", ".icu",
        ".club", ".link", ".tk", ".cf", ".gq", ".ml", ".ga", ".pw",
        ".rest", ".fun", ".monster", ".digital", ".cyou", ".world"
    )

    fun check(url: String): Result {
        val lower = url.lowercase().trim()
        val host = lower.removePrefix("https://").removePrefix("http://").substringBefore("/")
        val reasons = mutableListOf<String>()
        var score = 0

        // ── Verificações de golpe financeiro ────────────────────────────────
        val finMatch = financialKeywords.filter { lower.contains(it) }
        if (finMatch.isNotEmpty()) {
            score += (finMatch.size * 20).coerceAtMost(70)
            reasons.add("Palavras de golpe financeiro: ${finMatch.take(3).joinToString(", ")}")
        }

        // ── APK pirata explícito ─────────────────────────────────────────────
        val apkMatch = apkPiracyKeywords.firstOrNull { lower.contains(it) }
        if (apkMatch != null) {
            score += 60
            reasons.add("Distribuidor de APK modificado/pirata detectado ("$apkMatch")")
        }

        // ── "apk" + "mod" presentes no host (mesmo separados) ──────────────
        if (apkMatch == null) {
            val signals = apkSignalWords.filter { host.contains(it) }
            if (signals.size >= 2) {
                score += 50
                reasons.add("Combinação suspeita no domínio: ${signals.joinToString(" + ")} → site de malware/pirataria")
            } else if (signals.size == 1) {
                score += 20
                reasons.add("Domínio contém "\"${signals[0]}\"" — verifique se é uma fonte oficial")
            }
        }

        // ── Isca de jogo/app famoso ──────────────────────────────────────────
        val bait = baitNames.firstOrNull { lower.contains(it) }
        if (bait != null) {
            score += 35
            reasons.add("Nome de jogo/app popular como isca ("$bait") — baixe só pela loja oficial")
        }

        // ── TLD suspeito ────────────────────────────────────────────────────
        val tld = suspiciousTLDs.firstOrNull { host.endsWith(it) || host.contains("$it/") || host.contains("$it.") }
        if (tld != null) {
            score += 25
            reasons.add("Extensão de domínio ($tld) usada em golpes e phishing descartável")
        }

        // ── IP direto ────────────────────────────────────────────────────────
        if (Regex("""^(https?://)?(\d{1,3}\.){3}\d{1,3}""").containsMatchIn(lower)) {
            score += 40
            reasons.add("Acesso por IP direto — servidores legítimos usam domínio com nome")
        }

        // ── HTTP sem criptografia ─────────────────────────────────────────────
        if (lower.startsWith("http://")) {
            score += 15
            reasons.add("Sem criptografia (HTTP) — dados podem ser interceptados")
        }

        // ── Excesso de hifens ────────────────────────────────────────────────
        val hyphens = host.count { it == '-' }
        if (hyphens >= 3) {
            score += 20
            reasons.add("Muitos hifens ($hyphens) para imitar domínio oficial")
        } else if (hyphens >= 2) {
            score += 10
            reasons.add("Hifens para camuflar domínio (ex: banco-seguro-online.com)")
        }

        // ── Subdomínios excessivos ────────────────────────────────────────────
        val parts = host.split(".")
        if (parts.size >= 4) {
            score += 20
            reasons.add("Estrutura suspeita: \"${parts.dropLast(2).joinToString(".")}\" dentro de outro domínio")
        }

        // ── URL muito longa ───────────────────────────────────────────────────
        if (lower.length > 100) {
            score += 10
            reasons.add("URL muito longa (${lower.length} chars) — esconde destino real")
        }

        val finalScore = score.coerceAtMost(100)
        val verdict = when {
            finalScore >= 55 -> "FRAUDULENTO"
            finalScore >= 20 -> "SUSPEITO"
            else             -> "SEGURO"
        }

        return Result(finalScore, verdict, reasons)
    }
}
