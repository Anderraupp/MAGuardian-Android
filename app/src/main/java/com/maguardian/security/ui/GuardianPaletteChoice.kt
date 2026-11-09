package com.maguardian.security.ui

/** Stable local preference IDs; no account, billing or protection state. */
internal enum class GuardianPaletteChoice(val id: String, val label: String, val light: Boolean) {
    ORIGINAL("original", "Original — preto e vermelho", false),
    PETROLEO("petroleo", "Azul petróleo — claro", true),
    GOLD("gold", "Grafite e dourado", false);

    companion object {
        fun fromSaved(id: String?): GuardianPaletteChoice =
            entries.firstOrNull { it.id == id } ?: ORIGINAL
    }
}
