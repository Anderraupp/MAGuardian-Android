package com.maguardian.security.ui

/** Stable local preference IDs; no account, billing or protection state. */
internal enum class GuardianPaletteChoice(val id: String, val label: String, val light: Boolean) {
    ORIGINAL("original", "Preto e dourado", false),
    PETROLEO("petroleo", "Claro", true),
    GOLD("gold", "Vinho e dourado", false);

    companion object {
        fun fromSaved(id: String?): GuardianPaletteChoice =
            entries.firstOrNull { it.id == id } ?: ORIGINAL
    }
}
