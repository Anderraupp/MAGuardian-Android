package com.maguardian.security.util

import java.util.Locale

/** Identificação por nome; não é evidência de fraude nem altera o score de risco. */
object CarrierCallPolicy {
    private val carrierName = Regex(
        """(?<![\p{L}\p{N}_])(?:TIM|VIVO|CLARO)(?![\p{L}\p{N}_])""",
        RegexOption.IGNORE_CASE
    )

    fun identifiedCarrier(displayName: String): String? =
        carrierName.find(displayName)?.value?.uppercase(Locale.ROOT)
}
