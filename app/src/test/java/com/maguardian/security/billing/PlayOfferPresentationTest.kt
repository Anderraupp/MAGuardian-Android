package com.maguardian.security.billing

import android.app.Application
import com.android.billingclient.api.ProductDetails
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
class PlayOfferPresentationTest {
    private fun details(trial: Boolean, price: String = "R$ 12,34"): ProductDetails {
        val phases = JSONArray()
        if (trial) phases.put(JSONObject().put("billingPeriod", "P7D").put("priceAmountMicros", 0)
            .put("formattedPrice", "Grátis").put("priceCurrencyCode", "BRL").put("billingCycleCount", 1).put("recurrenceMode", 2))
        phases.put(JSONObject().put("billingPeriod", "P1M").put("priceAmountMicros", 12340000)
            .put("formattedPrice", price).put("priceCurrencyCode", "BRL").put("billingCycleCount", 0).put("recurrenceMode", 1))
        val json = JSONObject().put("productId", BillingManager.SKU_MONTHLY).put("type", "subs")
            .put("title", "Plano mensal").put("name", "Plano mensal").put("description", "Fixture de teste")
            .put("subscriptionOfferDetails", JSONArray().put(JSONObject().put("basePlanId", "mensal")
                .put("offerIdToken", "inert-test-token").put("pricingPhases", phases).put("offerTags", JSONArray())))
        return ProductDetails::class.java.getDeclaredConstructor(String::class.java).apply { isAccessible = true }.newInstance(json.toString())
    }
    @Test fun noProductMeansNoInventedPriceOrTrial() { assertNull(PlayOfferPresentation.from(null)) }
    @Test fun actualPaidOfferDoesNotAdvertiseLocalTrial() {
        val offer = PlayOfferPresentation.from(details(false))!!
        assertEquals("R$ 12,34/mês", offer.priceLabel)
        assertFalse(offer.sevenDayTrial)
    }
    @Test fun trialIsReadFromTheSameFirstOfferAsPurchase() {
        val offer = PlayOfferPresentation.from(details(true))!!
        assertTrue(offer.sevenDayTrial)
        assertEquals("R$ 12,34/mês", offer.priceLabel)
    }
}
