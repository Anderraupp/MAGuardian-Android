package com.maguardian.security.util

import org.junit.Assert.*
import org.junit.Test

class CarrierCallPolicyTest {
    @Test fun recognizesEachCarrierWithoutCaseSensitivity() {
        for ((label, expected) in listOf(
            "TIM" to "TIM", "tim" to "TIM", " TIM S.A. " to "TIM",
            "TIM / Black Friday" to "TIM",
            "Vivo" to "VIVO", "VIVO - Atendimento" to "VIVO",
            "Claro" to "CLARO", "CLARO (Brasil)" to "CLARO"
        )) {
            assertEquals(label, expected, CarrierCallPolicy.identifiedCarrier(label))
        }
    }

    @Test fun doesNotMatchPartsOfOtherNamesOrWords() {
        for (label in listOf(
            "", " ", "Timóteo", "TIMÓTEO", "Vitimado", "Declaro", "Clarovox",
            "VIVONET", "TIM123", "TIM_Contato", "Cláudio", "João"
        )) {
            assertNull(label, CarrierCallPolicy.identifiedCarrier(label))
        }
    }
}
