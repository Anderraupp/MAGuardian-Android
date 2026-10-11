package com.maguardian.security.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentPaddingTest {
    @Test fun preservesExistingPaddingWhenThereAreNoInsets() {
        val base = ContentPadding(16, 8, 16, 12)
        assertEquals(base, base.withInsets(0, 0, 0, 0))
    }
    @Test fun protectsAllEdgesIncludingLandscapeCutout() {
        assertEquals(ContentPadding(50, 36, 20, 38), ContentPadding(16, 8, 16, 12).withInsets(34, 28, 4, 26))
    }
    @Test fun repeatedDispatchDoesNotAccumulatePadding() {
        val base = ContentPadding(16, 8, 16, 12)
        repeat(3) {
            assertEquals(ContentPadding(16, 32, 16, 42), base.withInsets(0, 24, 0, 30))
        }
    }
    @Test fun removingKeyboardRestoresSystemBarPadding() {
        val base = ContentPadding(16, 8, 16, 12)
        assertEquals(312, base.withInsets(0, 24, 0, 300).bottom)
        assertEquals(42, base.withInsets(0, 24, 0, 30).bottom)
    }
}
