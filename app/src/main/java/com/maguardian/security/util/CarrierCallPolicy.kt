package com.maguardian.security.util

import java.util.Locale

/** Identificação por nome ou número relatado; não é evidência de fraude. */
object CarrierCallPolicy {
    // Correspondência exata: não estender denúncias a faixas ou números vizinhos.
    // O nome comercial visto no discador pode não chegar ao CallScreeningService.
    private val reportedCarrierNumbers = mapOf(
        "1142145253" to "TIM"
    )

    private val carrierName = Regex(
        """(?<![\p{L}\p{N}_])(?:TIM|VIVO|CLARO)(?![\p{L}\p{N}_])""",
        RegexOption.IGNORE_CASE
    )

    fun identifiedCarrier(displayName: String, number: String = ""): String? =
        reportedCarrierNumbers[PrefsHelper.normalizeForBlock(number)]
            ?: carrierName.find(displayName)?.value?.uppercase(Locale.ROOT)
}
