package com.maguardian.security.util

/**
 * Motor de análise estática de URLs — 100% offline, sem API externa.
 * Cobre 20+ categorias de golpes comuns no Brasil.
 */
object LinkChecker {

    data class Result(
        val score: Int,
        val verdict: String,
        val category: String,
        val reasons: List<String>
    )

    // ════════════════════════════════════════════════════════════════════════
    // 1. BANCOS E FINTECHS BRASILEIROS — alvos de phishing
    // ════════════════════════════════════════════════════════════════════════
    private val bankBrands = listOf(
        "itau", "itaú", "bradesco", "santander", "caixa", "bancodobrasil", "bb",
        "nubank", "inter", "c6bank", "picpay", "pagseguro", "pagbank", "mercadopago",
        "mercado-pago", "mercadolivre", "sicoob", "sicredi", "safra", "btgpactual",
        "modalmais", "xpinvestimentos", "xpi", "genialbank", "neon", "will", "next",
        "original", "bs2", "agibank", "banrisul", "daycoval", "pine", "bmg",
        "bancomaster", "sofisa", "digio", "portobank", "recargapay", "iti"
    )

    // ════════════════════════════════════════════════════════════════════════
    // 2. MARCAS GLOBAIS — alvos de phishing (e-commerce, tech, streaming)
    // ════════════════════════════════════════════════════════════════════════
    private val globalBrands = listOf(
        "amazon", "netflix", "spotify", "google", "microsoft", "apple", "paypal",
        "facebook", "instagram", "whatsapp", "telegram", "tiktok", "youtube",
        "shopee", "aliexpress", "magalu", "magazineluiza", "americanas", "casasbahia",
        "submarino", "carrefour", "kabum", "vivara", "hering", "riachuelo",
        "disneyplus", "hbomax", "globoplay", "playstationplus", "steam", "epicgames",
        "ifood", "rappi", "uber", "99taxi", "correios", "serasa", "spc"
    )

    // ════════════════════════════════════════════════════════════════════════
    // 3. ÓRGÃOS GOVERNAMENTAIS — impersonação
    // ════════════════════════════════════════════════════════════════════════
    private val govBrands = listOf(
        "receita", "receitafederal", "gov.br", "detran", "denatran", "anatel",
        "inss", "previdencia", "cnis", "fgts", "caixa-fgts", "sefaz", "tjsp",
        "procon", "anvisabr", "anvisa", "susep", "ibge", "serpro", "dataprev",
        "ministerio", "tribunal", "justicafederal", "policiafederal", "pf"
    )

    // ════════════════════════════════════════════════════════════════════════
    // 4. PALAVRAS DE PHISHING / URGÊNCIA
    // ════════════════════════════════════════════════════════════════════════
    private val phishingWords = listOf(
        "recadastro", "recadastre", "atualizar-dados", "atualizacao-cadastral",
        "bloqueada", "bloqueado", "suspensa", "suspensa-conta", "conta-encerrada",
        "urgente", "imediato", "acesso-negado", "acesso-bloqueado", "ultimo-aviso",
        "confirme-seus-dados", "verificar-identidade", "validar-cpf", "validar-conta",
        "desbloqueio", "reativacao", "regularize", "pendencia", "divida-ativa",
        "login-seguro", "secure-login", "verify-account", "account-verify",
        "update-payment", "payment-update", "confirm-now", "act-now"
    )

    // ════════════════════════════════════════════════════════════════════════
    // 5. GOLPES DE PIX / TRANSFERÊNCIA
    // ════════════════════════════════════════════════════════════════════════
    private val pixScamWords = listOf(
        "pix-premio", "pix-gratuito", "pix-sorteio", "cashback-pix", "pix-cashback",
        "pixfacil", "pix-bonus", "saque-pix", "resgate-pix", "pix-imediato",
        "transferencia-premiada", "deposito-premiado", "receber-pix", "liberar-pix",
        "chave-pix-gratis", "ativar-pix", "pixbr", "meupix", "pixbrasil"
    )

    // ════════════════════════════════════════════════════════════════════════
    // 6. GOLPES DE EMPREGO FALSO / RENDA EXTRA
    // ════════════════════════════════════════════════════════════════════════
    private val jobScamWords = listOf(
        "trabalheem-casa", "trabalhe-casa", "rendaextra", "renda-extra",
        "ganhe-dinheiro", "ganhandodinheiro", "dinheiro-facil", "faturar",
        "seja-afiliado", "afiliado-digital", "marketing-digital-facil",
        "vagas-home-office", "vaga-online", "recrutamento-urgente",
        "digitador-online", "trabalho-online-garantido", "lucro-garantido",
        "investimento-garantido", "retorno-garantido", "ganhos-diarios",
        "bot-de-lucro", "robo-trader", "trader-expert", "sinais-forex",
        "forex-gratis", "criptomoeda-gratis", "bitcoin-gratis", "cripto-bonus"
    )

    // ════════════════════════════════════════════════════════════════════════
    // 7. SORTEIOS / PRÊMIOS FALSOS
    // ════════════════════════════════════════════════════════════════════════
    private val prizeScamWords = listOf(
        "voce-ganhou", "voceganhou", "parabens-ganhador", "sorteio-premiado",
        "ganhador-selecionado", "premio-disponivel", "resgate-premio",
        "cupom-ganhador", "bilhete-premiado", "loteria-premiada",
        "scratch-card", "raspadinha-digital", "roleta-premiada",
        "fidelidade-premio", "cashback-especial", "bonus-exclusivo",
        "presente-surpresa", "recompensa-especial", "iphone-gratis",
        "tv-gratis", "carro-sorteio", "moto-gratis"
    )

    // ════════════════════════════════════════════════════════════════════════
    // 8. GOLPES DE SAÚDE / FARMÁCIA FALSA
    // ════════════════════════════════════════════════════════════════════════
    private val healthScamWords = listOf(
        "emagrecer-rapido", "emagrecimento-milagre", "perder-peso-rapido",
        "remedio-milagroso", "cura-definitiva", "tratamento-secreto",
        "viagra-generico", "cialis-barato", "anabolizante-original",
        "farmacia-sem-receita", "remedio-importado-barato",
        "suplemento-proibido", "queimador-gordura-poderoso",
        "aumentar-performance", "potencia-masculina", "cbd-oficial"
    )

    // ════════════════════════════════════════════════════════════════════════
    // 9. GOLPES DE SUPORTE TÉCNICO FALSO
    // ════════════════════════════════════════════════════════════════════════
    private val techSupportScamWords = listOf(
        "virus-detectado", "virus-encontrado", "malware-alert",
        "seu-pc-infectado", "computador-infectado", "celular-infectado",
        "limpeza-gratuita", "remover-virus-gratis", "suporte-microsoft",
        "microsoft-support-br", "apple-suporte-br", "google-suporte-br",
        "antivirus-gratis-download", "baixar-antivirus-br"
    )

    // ════════════════════════════════════════════════════════════════════════
    // 10. GOLPES DE ENTREGA / RASTREAMENTO FALSO
    // ════════════════════════════════════════════════════════════════════════
    private val deliveryScamWords = listOf(
        "pacote-retido", "encomenda-retida", "taxa-alfandegaria",
        "liberar-encomenda", "rastrear-pacote-now", "correios-liberacao",
        "dhl-taxa", "fedex-pagamento", "sedex-pendente", "entrega-suspensa",
        "agencia-aguardando", "retirar-pacote", "pacote-nao-entregue"
    )

    // ════════════════════════════════════════════════════════════════════════
    // 11. GOLPES DE ROMANCE / ENCONTROS
    // ════════════════════════════════════════════════════════════════════════
    private val romanceScamWords = listOf(
        "conhecer-mulheres", "conhecer-homens", "mulheres-solteiras",
        "match-garantido", "relacionamento-serio-gratis",
        "foto-intima", "video-privado", "conteudo-adulto-gratis",
        "onlyfans-gratis", "nude-gratis", "pack-gratis"
    )

    // ════════════════════════════════════════════════════════════════════════
    // 12. EXTENSÕES DE ARQUIVO EXECUTÁVEL / MALICIOSO NA URL
    // ════════════════════════════════════════════════════════════════════════
    private val maliciousExtensions = listOf(
        ".exe", ".msi", ".bat", ".cmd", ".ps1", ".vbs", ".scr", ".pif",
        ".jar", ".com", ".hta", ".cpl", ".reg", ".inf", ".lnk", ".dll",
        ".js", ".jse", ".wsf", ".wsh", ".vbe"
    )

    // ════════════════════════════════════════════════════════════════════════
    // 13. HOSPEDAGEM RAW / COMPARTILHAMENTO SEM DOMÍNIO PRÓPRIO
    // ════════════════════════════════════════════════════════════════════════
    private val rawHostingDomains = listOf(
        "raw.githubusercontent.com", "raw.githubu",
        "pastebin.com", "hastebin.com", "ghostbin.com", "rentry.co",
        "paste.ee", "filebin.net", "temp.sh", "file.io", "gofile.io",
        "transfer.sh", "anonfiles.com", "bayfiles.com", "zippyshare.com",
        "mediafire.com", "4shared.com", "sendspace.com", "uploadfiles.io",
        "1fichier.com", "katfile.com", "rapidgator.net", "nitroflare.com"
    )

    // ════════════════════════════════════════════════════════════════════════
    // 14. FERRAMENTAS DE CHEAT/HACK / MALWARE NOMEADO
    // ════════════════════════════════════════════════════════════════════════
    private val cheatToolNames = listOf(
        "sigmagui", "sigma-gui", "sigma-aim", "sigmahack",
        "weaim", "we-aim", "aimware", "nohope", "skinchanger",
        "multihack", "cheatengine", "cheat-engine", "pubghack",
        "freecheat", "wallhack", "aimbot", "speedhack", "esp-hack",
        "bypass-vac", "vac-bypass", "bepinex-mod",
        "kiddion", "kiddion-modest", "stand-mod", "phantom-x",
        "neverlose", "gamesense", "skeet-io", "onetap-io",
        "predator-hack", "hvh-cheat", "supergui", "pubslounge", "pubs-lounge"
    )

    // ════════════════════════════════════════════════════════════════════════
    // 15. APK PIRATA / MALWARE
    // ════════════════════════════════════════════════════════════════════════
    private val apkMalwareKeywords = listOf(
        "modapk", "apkmod", "mod-apk", "apk-mod", "moddedapk", "apkpremium",
        "latestmod", "crackedapk", "apkhack", "happymod", "apkdownload-",
        "modded", "cracked", "unlimited-coins", "unlimited-gems", "unlimited-cash",
        "hackgame", "cheatgame", "aimbotapk", "wallhackapk", "bypasshot"
    )
    private val apkSignalWords = listOf("apk", "mod", "crack", "hack", "cheat", "warez", "keygen")

    // ════════════════════════════════════════════════════════════════════════
    // 13. ISCAS COM NOMES DE APPS/JOGOS FAMOSOS
    // ════════════════════════════════════════════════════════════════════════
    private val baitNames = listOf(
        "gta-5", "gta-6", "gta5", "gta6", "freefire-", "freefiremax", "pubg-",
        "fortnite-", "valorant-", "minecraft-", "roblox-", "among-us", "callofduty-",
        "netflix-premium", "spotify-premium", "amazon-prime-gratis",
        "disneyplus-gratis", "hbomax-gratis", "globoplay-gratis",
        "whatsapp-gold", "whatsapp-plus", "whatsappgold", "whatsappplus",
        "instagrampro", "instagram-plus", "tiktokpro", "youtube-vanced"
    )

    // ════════════════════════════════════════════════════════════════════════
    // 14. ENCURTADORES DE URL — escondem o destino real
    // ════════════════════════════════════════════════════════════════════════
    private val urlShorteners = listOf(
        "bit.ly", "tinyurl.com", "t.co", "ow.ly", "goo.gl", "is.gd",
        "buff.ly", "adf.ly", "bc.vc", "clk.sh", "rb.gy", "cutt.ly",
        "short.io", "tiny.cc", "lnkd.in", "mcaf.ee", "chilp.it",
        "u.to", "v.gd", "qr.ae", "l.ead.me", "soo.gd", "bl.ink",
        "shorte.st", "exe.io", "ouo.io", "za.gl", "encurtar.net"
    )

    // ════════════════════════════════════════════════════════════════════════
    // 15. TLDs DE ALTO RISCO
    // ════════════════════════════════════════════════════════════════════════
    private val suspiciousTLDs = listOf(
        ".xyz", ".top", ".click", ".online", ".site", ".ru", ".cc", ".icu",
        ".club", ".link", ".tk", ".cf", ".gq", ".ml", ".ga", ".pw",
        ".rest", ".fun", ".monster", ".digital", ".cyou", ".world", ".vip",
        ".fit", ".buzz", ".live", ".shop", ".hair", ".beauty", ".loan",
        ".date", ".download", ".racing", ".stream", ".accountant", ".cricket",
        ".info", ".biz", ".mobi", ".name", ".pro", ".ws", ".in", ".to"
    )

    // ════════════════════════════════════════════════════════════════════════
    // GOLPE DE DEPÓSITO / "DEPOSITE E GANHE" / RENDA FÁCIL
    // ════════════════════════════════════════════════════════════════════════
    private val advanceFeeKeywords = listOf(
        "deposite", "depositar", "deposito-", "-deposito",
        "ganhe-", "-ganhe", "ganhar-dinheiro", "ganho-garantido",
        "saque-liberado", "saque-disponivel", "liberar-saque",
        "voce-foi-selecionado", "foi-selecionado", "selecionado-",
        "recompensa-", "-recompensa", "ativar-recompensa", "resgatar-recompensa",
        "lucro-rapido", "lucro-imediato", "dinheiro-na-hora",
        "ganhe-agora", "receba-agora", "resgate-agora",
        "multiplique", "dobrar-dinheiro", "dinheiro-dobrado",
        "cashback-garantido", "bonus-garantido", "retorno-imediato",
        "investimento-rapido", "renda-passiva-facil", "lucro-diario-garantido"
    )

    // ════════════════════════════════════════════════════════════════════════
    // 16. HOMÓGLIFOS — dígitos/chars substituindo letras (paypa1, g00gle)
    // ════════════════════════════════════════════════════════════════════════
    private val homoglyphMap = mapOf(
        '0' to 'o', '1' to 'l', '3' to 'e', '4' to 'a',
        '5' to 's', '6' to 'g', '7' to 't', '8' to 'b'
    )

    private fun deHomoglyph(s: String): String =
        s.map { homoglyphMap[it] ?: it }.joinToString("")

    // ════════════════════════════════════════════════════════════════════════
    // 17. CAMINHOS SUSPEITOS NA URL
    // ════════════════════════════════════════════════════════════════════════
    private val suspiciousPaths = listOf(
        "/login", "/signin", "/account", "/secure", "/verify", "/validate",
        "/update", "/confirm", "/auth", "/token", "/reset", "/recover",
        "/suspended", "/locked", "/blocked", "/reactivate", "/restore",
        "/payment", "/checkout", "/pay", "/invoice", "/billing",
        "/cpf", "/rg", "/dados-pessoais", "/meus-dados", "/atualizar",
        "/download.php", "/get.php", "/file.php", "/setup.exe", "/install.exe"
    )

    // ════════════════════════════════════════════════════════════════════════
    // FUNÇÃO PRINCIPAL
    // ════════════════════════════════════════════════════════════════════════
    fun check(url: String): Result {
        val lower = url.lowercase().trim()
        val withScheme = if (!lower.startsWith("http")) "https://$lower" else lower
        val noScheme = withScheme.removePrefix("https://").removePrefix("http://")
        val host     = noScheme.substringBefore("/").substringBefore("?")
        val path     = noScheme.removePrefix(host).lowercase()
        val hostNorm = deHomoglyph(host)     // versão com homóglifos resolvidos
        val reasons  = mutableListOf<String>()
        var score    = 0
        var topCategory = "Desconhecido"

        // ── 0. ARQUIVO EXECUTÁVEL NA URL — risco máximo ──────────────────────
        // Detecta antes de qualquer outra coisa. .exe, .msi, .bat, .ps1, .vbs etc.
        // num link são quase sempre malware — nenhum serviço legítimo envia assim.
        val urlNoQuery = lower.substringBefore("?").substringBefore("#")
        val execExt = maliciousExtensions.firstOrNull { urlNoQuery.endsWith(it) }
        if (execExt != null) {
            score += 80
            reasons.add(
                "URL aponta diretamente para arquivo executável ($execExt) — " +
                "jamais baixe .exe/.msi/.bat de links enviados por mensagem"
            )
            topCategory = "Download de Malware"
        }

        // ── 0b. HOSPEDAGEM RAW + executável → risco muito alto ───────────────
        val isRawHosting = rawHostingDomains.any { host == it || host.endsWith(".$it") }
        if (isRawHosting) {
            val bonus = if (execExt != null) 15 else 30
            score += bonus
            reasons.add(
                "Arquivo hospedado em serviço raw/compartilhamento (${host}) sem domínio próprio — " +
                "padrão clássico de distribuição de malware"
            )
            if (topCategory == "Desconhecido") topCategory = "Download Suspeito"
        }

        // ── 0c. FERRAMENTA DE CHEAT/HACK CONHECIDA ──────────────────────────
        val cheatHit = cheatToolNames.firstOrNull { lower.contains(it) }
        if (cheatHit != null) {
            score += 70
            reasons.add(
                "Ferramenta de cheat/hack conhecida (\"$cheatHit\") — " +
                "distribui malware, rouba contas e credenciais do jogo"
            )
            topCategory = "Malware / Cheat Tool"
        }

        // ── 1. Encurtador de URL ─────────────────────────────────────────────
        val shortener = urlShorteners.firstOrNull { host == it || host.endsWith(".$it") }
        if (shortener != null) {
            score += 30
            reasons.add("Encurtador de URL ($shortener) — destino real ocultado")
            topCategory = "Link Encurtado"
        }

        // ── 1b. Subdomínio "go." — redirect tracker de campanha ──────────────
        // go.nondi.info, go.anycrazysite.info etc. — padrão de rastreamento
        // de cliques usado em spam/golpes enviados por WhatsApp e SMS
        if (host.startsWith("go.") && !host.endsWith("google.com") && !host.endsWith("go.com")) {
            score += 35
            reasons.add("Subdomínio \"go.\" ($host) é padrão de link de rastreamento usado em golpes por WhatsApp e SMS — o destino real está oculto")
            if (topCategory == "Desconhecido" || topCategory == "Link Encurtado") topCategory = "Link Suspeito / Redirect"
        }

        // ── 2. IP direto ────────────────────────────────────────────────────
        if (Regex("""^(\d{1,3}\.){3}\d{1,3}""").containsMatchIn(host)) {
            score += 45
            reasons.add("Acesso por IP numérico — sites legítimos não enviam links assim")
            topCategory = "IP Direto"
        }

        // ── 3. HTTP sem criptografia ─────────────────────────────────────────
        if (lower.startsWith("http://")) {
            score += 15
            reasons.add("Sem criptografia (HTTP) — dados digitados podem ser roubados")
        }

        // ── 4. TLD suspeito ────────────────────────────────────────────────
        val tld = suspiciousTLDs.firstOrNull { host.endsWith(it) }
        if (tld != null) {
            score += 25
            reasons.add("Extensão ($tld) muito usada em golpes e phishing descartável")
            if (topCategory == "Desconhecido") topCategory = "Domínio Suspeito"
        }

        // ── 5. Banco/Fintech no domínio NÃO-OFICIAL ────────────────────────
        val bankHit = bankBrands.firstOrNull { brand ->
            (host.contains(brand) || hostNorm.contains(brand)) &&
            !host.endsWith("$brand.com.br") && !host.endsWith("$brand.com")
        }
        if (bankHit != null) {
            score += 55
            reasons.add("Nome de banco/fintech (\"$bankHit\") em domínio não-oficial — phishing")
            topCategory = "Phishing Bancário"
        }

        // ── 6. Marca global em domínio não-oficial ──────────────────────────
        val brandHit = if (bankHit == null) globalBrands.firstOrNull { brand ->
            (host.contains(brand) || hostNorm.contains(brand)) &&
            !host.endsWith("$brand.com") && !host.endsWith("$brand.com.br") &&
            !host.endsWith("$brand.net")
        } else null
        if (brandHit != null) {
            score += 45
            reasons.add("Marca famosa (\"$brandHit\") em domínio não-oficial — impersonação")
            topCategory = "Impersonação de Marca"
        }

        // ── 7. Órgão governamental fora de gov.br ──────────────────────────
        val govHit = govBrands.firstOrNull { brand ->
            (lower.contains(brand) || deHomoglyph(lower).contains(brand)) &&
            !host.endsWith(".gov.br") && !host.endsWith(".jus.br") &&
            !host.endsWith(".mil.br") && !host.endsWith(".leg.br")
        }
        if (govHit != null) {
            score += 60
            reasons.add("Órgão do governo (\"$govHit\") fora do domínio .gov.br — golpe")
            topCategory = "Golpe Governamental"
        }

        // ── 8. Palavras de phishing / urgência ──────────────────────────────
        val phishHits = phishingWords.filter { lower.contains(it) }
        if (phishHits.isNotEmpty()) {
            score += (phishHits.size * 15).coerceAtMost(50)
            reasons.add("Palavras de phishing: ${phishHits.take(3).joinToString(", ")}")
            if (topCategory == "Desconhecido") topCategory = "Phishing"
        }

        // ── 9. Golpe de PIX ──────────────────────────────────────────────────
        val pixHit = pixScamWords.firstOrNull { lower.contains(it) }
        if (pixHit != null) {
            score += 50
            reasons.add("Golpe de PIX: \"$pixHit\" — PIX não gera prêmios ou saques automáticos")
            topCategory = "Golpe de PIX"
        }

        // ── 10. Emprego falso / renda extra ──────────────────────────────────
        val jobHit = jobScamWords.firstOrNull { lower.contains(it) }
        if (jobHit != null) {
            score += 40
            reasons.add("Oferta suspeita de emprego/renda: \"$jobHit\" — esquema de pirâmide ou fraude")
            if (topCategory == "Desconhecido") topCategory = "Golpe de Emprego"
        }

        // ── 11. Sorteio / prêmio falso ───────────────────────────────────────
        val prizeHit = prizeScamWords.firstOrNull { lower.contains(it) }
        if (prizeHit != null) {
            score += 45
            reasons.add("Golpe de prêmio/sorteio: \"$prizeHit\" — não existe prêmio real")
            if (topCategory == "Desconhecido") topCategory = "Sorteio Falso"
        }

        // ── 12. Golpe de saúde / farmácia ────────────────────────────────────
        val healthHit = healthScamWords.firstOrNull { lower.contains(it) }
        if (healthHit != null) {
            score += 40
            reasons.add("Produto de saúde suspeito: \"$healthHit\" — pode ser estelionato ou medicamento proibido")
            if (topCategory == "Desconhecido") topCategory = "Saúde / Farmácia Falsa"
        }

        // ── 13. Suporte técnico falso ────────────────────────────────────────
        val techHit = techSupportScamWords.firstOrNull { lower.contains(it) }
        if (techHit != null) {
            score += 50
            reasons.add("Falso suporte técnico: \"$techHit\" — vírus ou suporte não são comunicados por link")
            if (topCategory == "Desconhecido") topCategory = "Suporte Falso"
        }

        // ── 14. Entrega / rastreamento falso ─────────────────────────────────
        val deliveryHit = deliveryScamWords.firstOrNull { lower.contains(it) }
        if (deliveryHit != null) {
            score += 45
            reasons.add("Golpe de entrega: \"$deliveryHit\" — Correios/transportadoras não cobram via link")
            if (topCategory == "Desconhecido") topCategory = "Entrega Falsa"
        }

        // ── 15. Romance / conteúdo adulto ────────────────────────────────────
        val romanceHit = romanceScamWords.firstOrNull { lower.contains(it) }
        if (romanceHit != null) {
            score += 40
            reasons.add("Golpe de romance ou conteúdo adulto falso: \"$romanceHit\"")
            if (topCategory == "Desconhecido") topCategory = "Romance / Conteúdo Falso"
        }

        // ── 15b. Golpe de depósito / "deposite e ganhe" ──────────────────────
        val advanceFeeHit = advanceFeeKeywords.firstOrNull { lower.contains(it) }
        if (advanceFeeHit != null) {
            score += 55
            reasons.add("Golpe de depósito/renda fácil: \"$advanceFeeHit\" — esquema \"deposite X e ganhe Y\" nunca é real, é estelionato")
            topCategory = "Golpe de Depósito / Renda Fácil"
        }

        // ── 16. APK pirata explícito ─────────────────────────────────────────
        val apkMatch = apkMalwareKeywords.firstOrNull { lower.contains(it) }
        if (apkMatch != null) {
            score += 65
            reasons.add("Distribuidor de APK modificado/malware: \"$apkMatch\"")
            topCategory = "Malware / APK Pirata"
        } else {
            val signals = apkSignalWords.filter { host.contains(it) }
            if (signals.size >= 2) {
                score += 50
                reasons.add("Combinação de palavras no domínio: ${signals.joinToString(" + ")} → site de malware")
                if (topCategory == "Desconhecido") topCategory = "Malware / APK Pirata"
            } else if (signals.size == 1) {
                score += 20
                reasons.add("Domínio contém \"${signals[0]}\" — verifique se é fonte oficial")
            }
        }

        // ── 17. Isca de app/jogo famoso ──────────────────────────────────────
        val bait = baitNames.firstOrNull { lower.contains(it) }
        if (bait != null) {
            score += 35
            reasons.add("Nome de jogo/app popular como isca: \"$bait\" — baixe apenas pela loja oficial")
            if (topCategory == "Desconhecido") topCategory = "APK / App Falso"
        }

        // ── 18. Homóglifos no domínio (paypa1.com, g00gle.com) ───────────────
        val hasHomoglyph = host.any { it.isDigit() } &&
            hostNorm != host &&
            (bankBrands + globalBrands).any { hostNorm.contains(it) }
        if (hasHomoglyph) {
            score += 55
            reasons.add("Domínio usa dígitos no lugar de letras para imitar marca legítima (ex: g00gle, paypa1)")
            if (topCategory == "Desconhecido") topCategory = "Domínio Disfarçado"
        }

        // ── 19. Caminhos suspeitos na URL ─────────────────────────────────────
        val suspPath = suspiciousPaths.firstOrNull { path.startsWith(it) || path.contains(it) }
        if (suspPath != null && score > 0) {
            score += 15
            reasons.add("Caminho suspeito na URL: \"$suspPath\"")
        }

        // ── 20. Estrutura de domínio suspeita ─────────────────────────────────
        val hyphens = host.count { it == '-' }
        when {
            hyphens >= 4 -> { score += 25; reasons.add("Excesso de hifens ($hyphens) para imitar domínio legítimo") }
            hyphens >= 2 -> { score += 12; reasons.add("Hifens para camuflar domínio (ex: banco-seguro-online.com)") }
        }

        val parts = host.split(".")
        if (parts.size >= 5) {
            score += 25
            reasons.add("Muitos subdomínios (${parts.size}) — esconde o domínio real no final")
        } else if (parts.size == 4) {
            score += 12
            reasons.add("Subdomínio suspeito: \"${parts.dropLast(2).joinToString(".")}\" dentro de outro domínio")
        }

        // ── 21. URL muito longa ───────────────────────────────────────────────
        if (lower.length > 150) {
            score += 15
            reasons.add("URL muito longa (${lower.length} chars) — esconde destino real com parâmetros")
        } else if (lower.length > 80 && score > 10) {
            score += 8
            reasons.add("URL longa (${lower.length} chars) — verifique o domínio principal")
        }

        // ── 22. Parâmetros suspeitos na query string ──────────────────────────
        if (lower.contains("redirect=") || lower.contains("url=http") ||
            lower.contains("goto=") || lower.contains("ref=pay")) {
            score += 20
            reasons.add("Parâmetro de redirecionamento — pode levar para site malicioso diferente")
        }

        // ── 23. Domínio muito recente (nome aleatório com muitas consoantes) ──
        val domainLabel = parts.dropLast(1).lastOrNull() ?: ""
        val vowels = domainLabel.count { it in "aeiouáéíóúãõâêô" }
        val cons   = domainLabel.count { it.isLetter() && it !in "aeiouáéíóúãõâêô" }
        if (domainLabel.length >= 8 && cons > 0 && vowels.toFloat() / cons.toFloat() < 0.25f) {
            score += 15
            reasons.add("Nome de domínio parece gerado aleatoriamente (poucas vogais) — padrão de phishing")
        }

        // ── Resultado final ───────────────────────────────────────────────────
        val finalScore = score.coerceAtMost(100)
        val verdict = when {
            finalScore >= 60 -> "FRAUDULENTO"
            finalScore >= 25 -> "SUSPEITO"
            else             -> "SEGURO"
        }
        val category = if (topCategory == "Desconhecido") {
            when {
                finalScore >= 60 -> "Link Perigoso"
                finalScore >= 25 -> "Link Suspeito"
                else             -> "Sem riscos detectados"
            }
        } else topCategory

        return Result(finalScore, verdict, category, reasons)
    }
}
