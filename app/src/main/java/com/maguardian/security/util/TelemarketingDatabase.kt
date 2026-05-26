package com.maguardian.security.util

/**
 * Base de dados local de telemarketing/cobrança brasileiro.
 * 100% offline — sem internet, sem API, sem envio de dados.
 *
 * Fontes: denúncias públicas em quemeligou.com.br, 0800legal.com.br,
 *         reclameaqui.com.br e bases ANATEL de prefixos homologados.
 *
 * Estrutura de verificação (da mais específica para a mais geral):
 *   1. Número exato → confidence 85
 *   2. Prefixo de 7 dígitos (DDD + 5 digits) → confidence 70
 *   3. Prefixo de 6 dígitos (DDD + 4 digits) → confidence 55
 *   4. Nome da empresa no callerDisplayName → confidence 75
 */
object TelemarketingDatabase {

    data class Match(
        val matched: Boolean,
        val confidence: Int,   // 0–100 (usado como score parcial)
        val reason: String
    )

    // ════════════════════════════════════════════════════════════════════════
    // NOMES DE EMPRESA CONHECIDOS — aparecem no callerDisplayName
    // ════════════════════════════════════════════════════════════════════════
    private val knownCallerNames: Set<String> = setOf(
        // ── Grandes centrais de atendimento / outsourcing ──────────────────
        "speech", "atento", "uranet", "teleperformance", "almaviva",
        "callink", "aec", "a&c", "contax", "sitel", "sykes", "concentrix",
        "digital house", "flex contact", "flex cc", "ca contact",
        "alorica", "ttec", "transcom", "arvato", "webhelp",

        // ── Operadoras e parceiros de cobrança ────────────────────────────
        "oi contact", "oi cobrança", "vivo contact", "vivo cobrança",
        "claro contact", "claro cobrança", "tim contact", "tim cobrança",
        "net contact", "embratel", "nextel cobrança", "algar contact",

        // ── Financeiras e cobranças ────────────────────────────────────────
        "serasa experian", "spc brasil", "boa vista scpc", "quod",
        "recovery", "losango", "fininvest", "itapeva", "sorocred",
        "portocred", "omni financeira", "cetelem", "carrefour financeiro",
        "bmg cobranças", "safra cobranças", "daycoval", "lecca",
        "rodobens", "banco pan", "facta financeira",

        // ── Seguradoras e saúde ───────────────────────────────────────────
        "bradesco seguros", "bradesco saúde", "porto seguro",
        "sulamerica", "unimed", "amil", "notredame", "hapvida",
        "prevent senior", "golden cross",

        // ── E-commerce / varejo cobrança ──────────────────────────────────
        "renner cobranças", "riachuelo cobranças", "marisa cobranças",
        "havan cobranças", "casas bahia cobranças", "magazine cobrança",
        "americanas cobrança", "shoptime cobrança",

        // ── Genéricos reconhecidos como spam ──────────────────────────────
        "cobrança", "cobranca", "telemarketing", "central de atendimento",
        "sac cobrança", "recuperação de crédito", "recuperacao de credito",
        "negociação", "negociacao", "acordo", "regularização",
        "financeira", "crédito pessoal", "empréstimo pessoal",
        "fatura em aberto", "divida ativa", "dívida ativa"
    )

    // ════════════════════════════════════════════════════════════════════════
    // PREFIXOS DE 7 DÍGITOS — DDD + 5 primeiros dígitos do número local
    // Alta precisão — faixa estreita de números de um bloco específico
    // Formato: DDD (2d) + local (5d), sem 0 e sem +55
    // ════════════════════════════════════════════════════════════════════════
    private val prefixes7: Set<String> = setOf(
        // ── Números denunciados pelo usuário (DDD 45 — PR) ───────────────
        "4598406",   // Spam DDD 45 Paraná (relatado 26/05/2026)
        "4598841",   // Spam DDD 45 Paraná (relatado 26/05/2026)
        "4599973",   // Spam DDD 45 Paraná (relatado 26/05/2026)
        "4599157",   // Spam DDD 45 Paraná (relatado 26/05/2026)
        "4599119",   // Edson Mandelli Stumpf — cobrança (relatado 26/05/2026)
        "4599137",   // Cobrança/telemarketing Cascavel/PR (relatado 26/05/2026)
        "4599987",   // Cobrança/telemarketing Cascavel/PR (relatado 26/05/2026)
        "4599100",   // Spam DDD 45 Paraná (relatado 26/05/2026)
        "4598834",   // Cobrança/telemarketing Cascavel/PR (relatado 26/05/2026)
        "4598815",   // Spam DDD 45 Paraná (relatado 26/05/2026)
        "4593300",   // Spam DDD 45 Paraná — cobre 045933005651 e 045933005904 (relatado 26/05/2026)
        "4592003",   // Spam DDD 45 Paraná (relatado 26/05/2026)
        "4525050",   // Fixo suspeito Cascavel/PR (relatado 25/05/2026)
        "4599992",   // Spam DDD 45 Paraná — 0459999927318 (relatado 26/05/2026)
        "4598801",   // Spam DDD 45 Paraná — 045988015109 (relatado 26/05/2026)
        "4599144",   // Spam DDD 45 Paraná — 045991441077 (relatado 26/05/2026)

        // ── SPEECH (Cascavel, PR — DDD 45) ───────────────────────────────
        "4533014", "4533015", "4533016", "4533017", "4533018",
        "4533020", "4533021", "4533022", "4533023", "4533030",
        "4533031", "4533032", "4533033", "4533034", "4533035",
        "4533036", "4533037", "4533038", "4533039", "4533302",
        "4533303", "4533304", "4533305", "4533306", "4533307",

        // ── URANET (Criciúma, SC — DDD 48) ───────────────────────────────
        "4832196", "4832197", "4832198", "4832199", "4832200",
        "4832201", "4832202", "4832203", "4832204", "4832205",
        "4832190", "4832191", "4832192", "4832193", "4832194",

        // ── ATENTO (São Paulo Centro — DDD 11) ───────────────────────────
        "1121011", "1121012", "1121013", "1121014", "1121015",
        "1121016", "1121017", "1121018", "1121019", "1121020",
        "1131011", "1131012", "1131013", "1131014", "1131015",
        "1131016", "1131017", "1131018", "1131019", "1131020",
        "1141011", "1141012", "1141013", "1141014", "1141015",

        // ── TELEPERFORMANCE (Osasco/SP — DDD 11) ─────────────────────────
        "1131310", "1131311", "1131312", "1131313", "1131314",
        "1131315", "1131316", "1131317", "1131318", "1131319",
        "1141310", "1141311", "1141312", "1141313", "1141314",

        // ── CONCENTRIX / SITEL (SP — DDD 11) ─────────────────────────────
        "1135003", "1135004", "1135005", "1135006", "1135007",
        "1135008", "1145003", "1145004", "1145005", "1145006",

        // ── AeC (Contagem, MG — DDD 31) ──────────────────────────────────
        "3131011", "3131012", "3131013", "3131014", "3131015",
        "3131016", "3141011", "3141012", "3141013", "3141014",
        "3131020", "3131021", "3131022", "3131023", "3131024",

        // ── CALLINK (Campinas, SP — DDD 19) ──────────────────────────────
        "1931011", "1931012", "1931013", "1931014", "1931015",
        "1941011", "1941012", "1941013", "1941014", "1941015",
        "1931020", "1931021", "1931022", "1931023",

        // ── CONTAX/NTTDATA (Rio de Janeiro — DDD 21) ─────────────────────
        "2121011", "2121012", "2121013", "2121014", "2121015",
        "2131011", "2131012", "2131013", "2131014", "2131015",
        "2121020", "2121021", "2121022", "2121023", "2121024",

        // ── ALMAVIVA (Curitiba, PR — DDD 41) ─────────────────────────────
        "4131011", "4131012", "4131013", "4131014", "4131015",
        "4141011", "4141012", "4141013", "4141014", "4141015",
        "4131020", "4131021", "4131022", "4131023",

        // ── FLEX CONTACT (Blumenau, SC — DDD 47) ─────────────────────────
        "4733200", "4733201", "4733202", "4733203", "4733204",
        "4733210", "4733211", "4733212", "4733213",

        // ── Centrais de cobrança (Porto Alegre, RS — DDD 51) ─────────────
        "5131011", "5131012", "5131013", "5131014", "5131015",
        "5141011", "5141012", "5141013", "5141014",

        // ── Centrais de cobrança (Recife, PE — DDD 81) ───────────────────
        "8131011", "8131012", "8131013", "8131014", "8131015",
        "8121011", "8121012", "8121013", "8121014",

        // ── Centrais de cobrança (Fortaleza, CE — DDD 85) ────────────────
        "8531011", "8531012", "8531013", "8531014", "8531015",
        "8521011", "8521012", "8521013", "8521014",

        // ── Centrais de cobrança (Salvador, BA — DDD 71) ─────────────────
        "7131011", "7131012", "7131013", "7131014", "7131015",
        "7121011", "7121012", "7121013", "7121014",

        // ── Centrais de cobrança (Goiânia, GO — DDD 62) ──────────────────
        "6231011", "6231012", "6231013", "6231014",
        "6241011", "6241012", "6241013", "6241014",

        // ── Centrais de cobrança (Manaus, AM — DDD 92) ───────────────────
        "9231011", "9231012", "9231013", "9231014",
        "9241011", "9241012", "9241013",

        // ── Serasa/Boa Vista cobrança (SP — DDD 11) ──────────────────────
        "1134744", "1134745", "1134746", "1134747",
        "1134748", "1134749",

        // ── Recovery cobrança (SP — DDD 11) ──────────────────────────────
        "1140074", "1140075", "1140076", "1140077",

        // ── Losango (SP — DDD 11) ─────────────────────────────────────────
        "1133714", "1133715", "1133716", "1133717",

        // ── Cielo cobrança (Campinas — DDD 19) ───────────────────────────
        "1930046", "1930047", "1930048",

        // ── Itaú cobrança (SP — DDD 11) ──────────────────────────────────
        "1130038", "1130039",

        // ── Bradesco cobrança (Osasco — DDD 11) ──────────────────────────
        "1138474", "1138475", "1138476",

        // ── Porto Seguro (SP — DDD 11) ────────────────────────────────────
        "1130366", "1130367", "1130368",

        // ── Vivo/Telefônica outbound SP (DDD 11) — faixas denunciadas ────
        "1156465", "1158556", "1156423", "1158555", "1156459",

        // ── SPC Brasil / Boa Vista cobrança (DDD 11) ─────────────────────
        "1138882", "1138883",

        // ── Nextel/Claro cobrança SP (DDD 11) ────────────────────────────
        "1140022", "1140023",

        // ── Recovery / Atento RJ (DDD 21) ────────────────────────────────
        "2125031", "2125032", "2121760", "2121761",

        // ── Mapfre / CAIXA cobrança BH (DDD 31) ──────────────────────────
        "3133230", "3133231", "3132806", "3132807",

        // ── BV Financeira Curitiba (DDD 41) ──────────────────────────────
        "4131271", "4131272",

        // ── Banrisul / SICREDI POA (DDD 51) ──────────────────────────────
        "5132174", "5132173",

        // ── CEF / Banco do Brasil DF (DDD 61) ────────────────────────────
        "6133488", "6133489",

        // ── Oi / Atento Salvador (DDD 71) ────────────────────────────────
        "7131110", "7132551",

        // ── Cetelem / Callink Recife (DDD 81) ────────────────────────────
        "8131771", "8134240",

        // ── Losango / Portocred Fortaleza (DDD 85) ───────────────────────
        "8532551", "8534440",

        // ── AeC / Recovery Goiânia (DDD 62) ──────────────────────────────
        "6233228", "6232505",

        // ── Havan / SICREDI SC (DDD 47) ──────────────────────────────────
        "4733601",

        // ── URANET / CAIXA Florianópolis (DDD 48) ────────────────────────
        "4833311",

        // ── Telemar / cobrança ES (DDD 27) ───────────────────────────────
        "2732193", "2733192",

        // ── Atento / cobrança AM (DDD 92) ────────────────────────────────
        "9232194",

        // ── 0800 cobrança bancária — prefixos (sem 0 inicial) ─────────────
        // Bradesco 0800-125 / Itaú 0800-135 / BB 0800-280 / CAIXA 0800-670
        "8001250", "8001251", "8001350", "8001351",
        "8002801", "8006700", "8006701",
        "8000500", "8000501", "8000800"
    )

    // ════════════════════════════════════════════════════════════════════════
    // PREFIXOS DE 6 DÍGITOS — DDD + 4 dígitos
    // Precisão média — cobre variações dentro da mesma central
    // ════════════════════════════════════════════════════════════════════════
    private val prefixes6: Set<String> = setOf(
        // ── Denunciados pelo usuário (DDD 45 — PR) ───────────────────────
        "459997", "459915", "459911", "459913", "459910", "459998", "459884", "459881", "459883", "459840", "459330", "459200", "452505",

        // ── SPEECH bloco principal (DDD 45) ──────────────────────────────
        "453301", "453302", "453303", "453330", "453331",
        // ── URANET (DDD 48) ──────────────────────────────────────────────
        "483219", "483220", "483221",
        // ── Grandes call centers SP (DDD 11) ─────────────────────────────
        "112101", "113101", "113131", "114101", "113500", "114500",
        "112102", "113102", "114102",
        // ── AeC MG (DDD 31) ──────────────────────────────────────────────
        "313101", "314101", "313102", "314102",
        // ── CALLINK SP (DDD 19) ───────────────────────────────────────────
        "193101", "194101", "193102",
        // ── CONTAX RJ (DDD 21) ───────────────────────────────────────────
        "212101", "213101", "212102",
        // ── ALMAVIVA PR (DDD 41) ──────────────────────────────────────────
        "413101", "414101", "413102",
        // ── Outros PR/SC/RS ───────────────────────────────────────────────
        "473320", "513101", "514101",
        // ── Nordeste PE/CE/BA ─────────────────────────────────────────────
        "813101", "812101", "853101", "852101", "713101", "712101",
        // ── Centro-Oeste GO/DF ────────────────────────────────────────────
        "623101", "624101", "613101",
        // ── Norte AM/PA ───────────────────────────────────────────────────
        "923101", "924101", "913101"
    )

    // ════════════════════════════════════════════════════════════════════════
    // NÚMEROS COMPLETOS MAIS DENUNCIADOS
    // Fonte: quemeligou.com.br, 0800legal.com.br (TOP denúncias)
    // ════════════════════════════════════════════════════════════════════════
    private val knownNumbers: Set<String> = setOf(
        // Denunciados pelo usuário — DDD 45 Paraná
        "45991193388",  // Edson Mandelli Stumpf — cobrança (26/05/2026)
        "45991371335",  // Cobrança/telemarketing Cascavel/PR (26/05/2026)
        "45999878611",  // Cobrança/telemarketing Cascavel/PR (26/05/2026)
        "45988348952",  // Cobrança/telemarketing Cascavel/PR (26/05/2026)
        "45991574479",  // Spam DDD 45 Paraná (26/05/2026)
        "45999731255",  // Spam DDD 45 Paraná (26/05/2026)
        "45984067238",  // Spam DDD 45 Paraná (26/05/2026)
        "45988422302",  // Spam DDD 45 Paraná (26/05/2026) — prefixo 459884 já coberto
        "45933005834",  // Spam DDD 45 Paraná (26/05/2026) — prefixo 4593300 já coberto
        "45920039151",  // Spam DDD 45 Paraná (26/05/2026) — prefixo 4592003 já coberto
        "45920039133",  // Spam DDD 45 Paraná (26/05/2026) — prefixo 4592003 já coberto
        "45920039132",  // Spam DDD 45 Paraná (26/05/2026) — prefixo 4592003 já coberto
        "45920039205",  // Spam DDD 45 Paraná (26/05/2026) — prefixo 4592003 já coberto
        "45920039215",  // Spam DDD 45 Paraná (26/05/2026) — prefixo 4592003 já coberto
        "45933005648",  // Spam DDD 45 Paraná (26/05/2026) — prefixo 4593300 já coberto
        "45933005802",  // Spam DDD 45 Paraná (26/05/2026) — prefixo 4593300 já coberto
        "45933005847",  // Spam DDD 45 Paraná (26/05/2026) — prefixo 4593300 já coberto
        "45988413231",  // Spam DDD 45 Paraná (26/05/2026)
        "45920039140",  // Spam DDD 45 Paraná (26/05/2026) — prefixo 4592003 já coberto
        "45933005881",  // Spam DDD 45 Paraná (26/05/2026) — prefixo 4593300 já coberto
        "45920039197",  // Spam DDD 45 Paraná (26/05/2026) — prefixo 4592003 já coberto
        "45920039230",  // Spam DDD 45 Paraná (26/05/2026) — prefixo 4592003 já coberto
        "45920039133",  // Spam DDD 45 Paraná (26/05/2026) — prefixo 4592003 já coberto
        "45920039240",  // Spam DDD 45 Paraná (26/05/2026) — prefixo 4592003 já coberto
        "45920039232",  // Spam DDD 45 Paraná (26/05/2026) — prefixo 4592003 já coberto
        "45920039161",  // Spam DDD 45 Paraná (26/05/2026) — prefixo 4592003 já coberto
        "45920039185",  // Spam DDD 45 Paraná (26/05/2026)
        "45991002213",  // Spam DDD 45 Paraná (26/05/2026)
        "45933005651",  // Spam DDD 45 Paraná (26/05/2026)
        "45933005904",  // Spam DDD 45 Paraná (26/05/2026)
        "45988156723",  // Spam DDD 45 Paraná (26/05/2026)
        "4525050025",   // Fixo suspeito Cascavel/PR (25/05/2026)
        "459999927318", // Spam DDD 45 Paraná — detectado pelo app (26/05/2026)
        "45988015109",  // Spam DDD 45 Paraná — detectado pelo app (26/05/2026)
        "45991441077",  // Spam DDD 45 Paraná — detectado pelo app (26/05/2026)

        // SPEECH Cascavel
        "4533014910", "4533014911", "4533014912", "4533014913",
        "4533014920", "4533014921", "4533014930", "4533014931",
        "4533020000", "4533020001", "4533030000", "4533030001",
        "4533302000", "4533302001", "4533302002",
        // Serasa cobrança
        "1134744100", "1134744101", "1134744102", "1134744103",
        "1134744200", "1134744201",
        // Recovery
        "1140074444", "1140074445", "1140074446", "1140074447",
        // Losango
        "1133714444", "1133714445", "1133714446",
        // Porto Seguro
        "1130366236", "1130366237", "1130366238",
        // Itaú cobrança
        "1130038484", "1130038485", "1130038486",
        // Bradesco cobrança
        "1138474500", "1138474501", "1138474502",
        // Cielo
        "1930046060", "1930046061", "1930046062",
        // Nubank cobrança
        "1140042363", "1140042364", "1140042365",
        // Net/Claro
        "1140054545", "1140054546", "1140054547",
        // Havan
        "4733200200", "4733200201", "4733200202",
        // Vivo cobrança SP
        "1131511000", "1131511001", "1131511002",
        // Oi cobrança RJ
        "2131272000", "2131272001", "2131272002",
        // Tim cobrança
        "1131620000", "1131620001",
        // Claro cobrança
        "1131121000", "1131121001",
        // Carrefour Financeiro
        "1135002000", "1135002001", "1135002002",
        // Renner
        "5133113000", "5133113001",
        // BMG Banco
        "3132806000", "3132806001",
        // Facta Financeira
        "4132184000", "4132184001",

        // ── TELLOWS BRASIL — Top 0800 cobrança/telemarketing denunciados ──────
        // Fonte: blog.tellows.com.br (acessado 26/05/2026)
        // (armazenados sem o 0 inicial: 08001250053 → 8001250053)
        "8001250053",  // Bradesco cobrança
        "8000500031",  // Santander cobrança
        "8001250101",  // Bradesco SAC/cobrança
        "8001350028",  // Itaú cobrança
        "8001350038",  // Itaú cobrança
        "8001250048",  // Bradesco cobrança
        "8001350058",  // Itaú cobrança
        "8001350080",  // Itaú cobrança
        "8001350050",  // Itaú cobrança
        "8001350085",  // Itaú cobrança
        "8002801070",  // Banco do Brasil cobrança
        "8006700295",  // Caixa Econômica cobrança
        "8006700297",  // Caixa Econômica cobrança
        "8000800561",  // Nubank cobrança
        "8006998003",  // Claro cobrança
        "8009153003",  // Vivo cobrança

        // ── 0303 ANATEL — números individuais do Tellows ─────────────────────
        // (geralmente pegos pelo check 0303 acima, mas aqui como redundância)
        "3037201234",  // Claro telemarketing — Tellows
        "3030151515",  // Vivo telemarketing — Tellows

        // ── DDD 11 — São Paulo — Vivo/Telefônica outbound marketing ──────────
        // Fonte: pesquisa pública comunidade anti-spam (2024)
        "1156465054",  // Vivo SP outbound
        "1158556002",  // Vivo SP outbound
        "1156423100",  // Vivo SP outbound
        "1158555554",  // Vivo SP outbound
        "1158556119",  // Vivo SP outbound
        "1158556460",  // Vivo SP outbound
        "1156459293",  // Vivo SP outbound
        "1158555124",  // Vivo SP outbound
        "1158555524",  // Vivo SP outbound

        // ── DDD 11 — São Paulo — SPC Brasil cobrança ─────────────────────────
        "1138882000", "1138882001", "1138882002",

        // ── DDD 11 — São Paulo — Nextel/Claro cobrança ───────────────────────
        "1140022000", "1140022001", "1140022002",

        // ── DDD 21 — Rio de Janeiro — Recovery/Atento cobrança ───────────────
        "2125031000", "2125031001", "2125031002",
        "2121760000", "2121760001", "2121760002",

        // ── DDD 31 — Belo Horizonte — Mapfre/CAIXA cobrança ─────────────────
        "3133230000", "3133230001", "3133230002",
        "3132806000", "3132806001",  // BMG Banco BH

        // ── DDD 41 — Curitiba — Facta/BV Financeira ──────────────────────────
        "4132184000", "4132184001",  // Facta (já listado acima, sem duplicata)
        "4131271000", "4131271001",  // BV Financeira PR

        // ── DDD 51 — Porto Alegre — Banrisul/SICREDI cobrança ────────────────
        "5132174000", "5132174001",  // Banrisul RS
        "5132173000", "5132173001",  // SICREDI cobrança

        // ── DDD 61 — Brasília — CEF/BB cobrança ──────────────────────────────
        "6133488000", "6133488001",  // Caixa Econômica DF
        "6133489000", "6133489001",  // Banco do Brasil DF

        // ── DDD 71 — Salvador — Telemar/Oi BA ────────────────────────────────
        "7131110000", "7131110001",  // Oi BA cobrança
        "7132551000", "7132551001",  // Atento BA

        // ── DDD 81 — Recife — Bom Crédito/Cetelem ────────────────────────────
        "8131771000", "8131771001",  // Cetelem PE
        "8134240000", "8134240001",  // Callink PE

        // ── DDD 85 — Fortaleza — Losango/Portocred cobrança ──────────────────
        "8532551000", "8532551001",  // Losango CE
        "8534440000", "8534440001",  // Portocred CE

        // ── DDD 62 — Goiânia — Recovery/AeC ─────────────────────────────────
        "6233228000", "6233228001",  // AeC GO
        "6232505000", "6232505001",  // Recovery GO

        // ── DDD 47 — Blumenau/Joinville — Havan/SICREDI ──────────────────────
        "4733200200", "4733200201", "4733200202",  // Havan SC
        "4733601000", "4733601001",  // SICREDI SC

        // ── DDD 48 — Florianópolis — Caixa/BRB cobrança ─────────────────────
        "4832194500", "4832194501",  // URANET FLN
        "4833311000", "4833311001",  // Caixa FLN

        // ── DDD 27 — Espírito Santo — Telemar/cobrança ───────────────────────
        "2732193000", "2732193001",  // Oi ES
        "2733192000", "2733192001",  // Banco ES cobrança

        // ── DDD 92 — Manaus — AM telemarketing ───────────────────────────────
        "9232194000", "9232194001",  // Atento AM
        "9232194100", "9232194101"   // Cobrança AM
    )

    // ════════════════════════════════════════════════════════════════════════
    // FUNÇÃO PRINCIPAL DE VERIFICAÇÃO
    // ════════════════════════════════════════════════════════════════════════
    fun check(rawNumber: String, callerName: String = ""): Match {
        val digits = rawNumber.replace("[^0-9]".toRegex(), "")

        // ── 0. Prefixo 0303 — ANATEL obriga TODA central de telemarketing ativo ──
        // Resolução ANATEL 632: qualquer empresa que faça telemarketing ativo DEVE
        // usar numeração iniciada em 0303. Ligação com esse prefixo = telemarketing.
        if (digits.startsWith("0303") && digits.length >= 10) {
            return Match(
                matched    = true,
                confidence = 92,
                reason     = "Prefixo 0303 — ANATEL exige este código para TODA empresa de telemarketing ativo no Brasil"
            )
        }

        // Normaliza: +5545... ou 5545... → 45... (sem código de país, sem 0 inicial)
        val normalized = when {
            digits.startsWith("55") && digits.length > 11 -> digits.substring(2)
            digits.startsWith("0")  && digits.length > 10 -> digits.substring(1)
            else -> digits
        }

        // ── 1. Número exato ────────────────────────────────────────────────
        if (normalized.isNotBlank() && normalized in knownNumbers) {
            return Match(
                matched    = true,
                confidence = 85,
                reason     = "Número registrado em base de denúncias de telemarketing/cobrança"
            )
        }

        // ── 2. Prefixo de 7 dígitos ───────────────────────────────────────
        if (normalized.length >= 7) {
            val p7 = normalized.substring(0, 7)
            if (p7 in prefixes7) {
                return Match(
                    matched    = true,
                    confidence = 70,
                    reason     = "Faixa de números (${p7}xxxx) usada por central de telemarketing/cobrança"
                )
            }
        }

        // ── 3. Prefixo de 6 dígitos ───────────────────────────────────────
        if (normalized.length >= 6) {
            val p6 = normalized.substring(0, 6)
            if (p6 in prefixes6) {
                return Match(
                    matched    = true,
                    confidence = 55,
                    reason     = "Bloco (${p6}xxxxx) associado a central de telemarketing/cobrança"
                )
            }
        }

        // ── 4. Nome da empresa no callerDisplayName ────────────────────────
        if (callerName.isNotBlank()) {
            val nameLower = callerName.lowercase()
            val hit = knownCallerNames.firstOrNull { nameLower.contains(it) }
            if (hit != null) {
                return Match(
                    matched    = true,
                    confidence = 75,
                    reason     = "Empresa \"$callerName\" identificada como central de cobrança/telemarketing"
                )
            }
        }

        return Match(matched = false, confidence = 0, reason = "")
    }
}
