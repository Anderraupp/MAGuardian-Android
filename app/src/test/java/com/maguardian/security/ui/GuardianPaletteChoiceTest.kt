package com.maguardian.security.ui

import org.junit.Assert.*
import org.junit.Test

class GuardianPaletteChoiceTest {
    @Test fun displayedNamesAreShortWithoutChangingSavedIds() {
        assertEquals(listOf("Preto e dourado", "Claro", "Vinho e dourado"), GuardianPaletteChoice.entries.map { it.label })
        assertEquals(listOf("original", "petroleo", "gold"), GuardianPaletteChoice.entries.map { it.id })
    }
    @Test fun allThreeChoicesHaveDistinctPersistentIds() {
        assertEquals(3, GuardianPaletteChoice.entries.size)
        assertEquals(3, GuardianPaletteChoice.entries.map { it.id }.toSet().size)
    }

    @Test fun everySavedSelectionRestoresTheSamePalette() {
        GuardianPaletteChoice.entries.forEach {
            assertEquals(it, GuardianPaletteChoice.fromSaved(it.id))
        }
    }

    @Test fun missingOrUnknownPreferenceUsesOriginal() {
        assertEquals(GuardianPaletteChoice.ORIGINAL, GuardianPaletteChoice.fromSaved(null))
        assertEquals(GuardianPaletteChoice.ORIGINAL, GuardianPaletteChoice.fromSaved("unknown"))
    }

    @Test fun onlyPetroleoUsesLightSystemBars() {
        assertTrue(GuardianPaletteChoice.PETROLEO.light)
        assertFalse(GuardianPaletteChoice.ORIGINAL.light)
        assertFalse(GuardianPaletteChoice.GOLD.light)
    }
}
