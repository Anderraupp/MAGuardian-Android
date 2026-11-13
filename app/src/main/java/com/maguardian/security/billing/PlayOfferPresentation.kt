package com.maguardian.security.billing

import com.android.billingclient.api.ProductDetails

/** Presentation of exactly the first offer already used by BillingManager.purchase. */
data class PlayOfferPresentation(val price: String, val sevenDayTrial: Boolean, val billingPeriod: String, val conditions: String) {
    val priceLabel: String get() = when (billingPeriod) {
        "P1M" -> "$price/mês"
        "P1Y" -> "$price/ano"
        else -> "$price · período $billingPeriod"
    }
    companion object {
        fun from(details: ProductDetails?): PlayOfferPresentation? {
            val offer = details?.subscriptionOfferDetails?.firstOrNull() ?: return null
            val phases = offer.pricingPhases.pricingPhaseList
            val paid = phases.lastOrNull { it.priceAmountMicros > 0 } ?: return null
            val trial = phases.firstOrNull()?.let {
                it.priceAmountMicros == 0L && it.billingPeriod in setOf("P7D", "P1W") && it.billingCycleCount == 1
            } == true
            fun period(value: String) = when (value) {
                "P7D", "P1W" -> "7 dias"
                "P1M" -> "1 mês"
                "P1Y" -> "1 ano"
                else -> value
            }
            val introductory = phases.dropLast(1).map { phase ->
                if (phase.priceAmountMicros == 0L) "${period(phase.billingPeriod)} grátis"
                else "${phase.formattedPrice} por ${period(phase.billingPeriod)}, durante ${phase.billingCycleCount} período(s)"
            }
            val regular = when (paid.billingPeriod) { "P1M" -> "${paid.formattedPrice}/mês"; "P1Y" -> "${paid.formattedPrice}/ano"; else -> "${paid.formattedPrice} por ${period(paid.billingPeriod)}" }
            val conditions = if (introductory.isEmpty()) "" else (introductory + "Depois, $regular.").joinToString("\n")
            return PlayOfferPresentation(paid.formattedPrice, trial, paid.billingPeriod, conditions)
        }
    }
}
