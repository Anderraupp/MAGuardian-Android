package com.maguardian.security.util

import org.junit.Assert.*
import org.junit.Test

class ReportedTelemarketingNumberTest {
    @Test fun reportedNumberIsRecognizedInBrazilianAndInternationalFormats() {
        for (number in listOf(
            "04141022516", "4141022516", "+554141022516", "554141022516",
            "(41) 4102-2516", "+55 (41) 4102-2516"
        )) {
            val match = TelemarketingDatabase.check(number, "TIM")
            assertTrue("Número denunciado não reconhecido: $number", match.matched)
            assertEquals(85, match.confidence)
            assertTrue(PhoneAnalyzer.analyze(number).score >= 25)
        }
    }

    @Test fun reportDoesNotBlockNeighboringNumbersOrTheCarrierName() {
        for (number in listOf("4141022515", "4141022517", "+554141022517")) {
            assertFalse("Não deve ampliar a denúncia para $number", TelemarketingDatabase.check(number, "TIM").matched)
            assertTrue(PhoneAnalyzer.analyze(number).score < 25)
        }
    }
}
