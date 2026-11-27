package ru.ventusceleri.pary

import androidx.compose.ui.graphics.Color

/** Colour set for one appearance. Same values as the iOS app (ios/Shared/Theme.swift). */
data class Swatch(
    val bg: Color, val card: Color, val ink: Color, val muted: Color,
    val accent: Color, val exam: Color, val line: Color,
) {
    companion object {
        fun of(bg: Long, card: Long, ink: Long, muted: Long, accent: Long, exam: Long, line: Long) =
            Swatch(c(bg), c(card), c(ink), c(muted), c(accent), c(exam), c(line))

        private fun c(rgb: Long) = Color(0xFF000000 or rgb)
    }
}

data class Palette(val light: Swatch, val dark: Swatch) {
    fun colors(dark: Boolean) = if (dark) this.dark else light
}

/** Persisted by key: do not rename. */
enum class ThemeId(val key: String, val title: String, val palette: Palette) {
    SAND("sand", "Песок", Palette(
        Swatch.of(0xF4EFE6, 0xFFFAF2, 0x14212E, 0x6B7480, 0xC96F12, 0xB4372B, 0xE4DCCD),
        Swatch.of(0x0B1622, 0x13202E, 0xECE6DA, 0x93A0AD, 0xF0A04B, 0xFF7B6B, 0x223344))),
    OCEAN("ocean", "Океан", Palette(
        Swatch.of(0xEEF4F8, 0xFFFFFF, 0x0F2235, 0x5D6F80, 0x1F6FB2, 0xC0392B, 0xD6E2EC),
        Swatch.of(0x0A1420, 0x122233, 0xE6EEF5, 0x8EA3B5, 0x5FB0FF, 0xFF7B6B, 0x1F3347))),
    FOREST("forest", "Лес", Palette(
        Swatch.of(0xEEF3EC, 0xFBFDF9, 0x18261B, 0x667366, 0x2F7D4A, 0xB4372B, 0xD7E2D3),
        Swatch.of(0x0C1510, 0x15211A, 0xE3ECE4, 0x8FA194, 0x6FCF8F, 0xFF7B6B, 0x22342A))),
    GRAPHITE("graphite", "Графит", Palette(
        Swatch.of(0xF2F2F4, 0xFFFFFF, 0x1C1C1E, 0x6E6E73, 0x3A3A3C, 0xC0392B, 0xDEDEE3),
        Swatch.of(0x000000, 0x1C1C1E, 0xF2F2F7, 0x98989F, 0xE5E5EA, 0xFF6B5E, 0x2C2C2E))),
    LILAC("lilac", "Сирень", Palette(
        Swatch.of(0xF4F0F8, 0xFDFBFF, 0x241A33, 0x72688A, 0x7B4FC9, 0xB4372B, 0xE2D9EE),
        Swatch.of(0x140F1E, 0x1E1730, 0xECE6F5, 0xA297B8, 0xB893FF, 0xFF7B8B, 0x2E2445))),
    SUNSET("sunset", "Закат", Palette(
        Swatch.of(0xFBEFED, 0xFFFAF9, 0x2E1614, 0x85625E, 0xD1495B, 0x9C2A1F, 0xF0D9D5),
        Swatch.of(0x1A0D10, 0x26151A, 0xF5E6E6, 0xB39497, 0xFF7A8A, 0xFFB36B, 0x3A2228))),
    MINT("mint", "Мята", Palette(
        Swatch.of(0xEAF6F3, 0xFAFFFD, 0x10302A, 0x5B7A73, 0x0E9384, 0xC0392B, 0xD0E8E2),
        Swatch.of(0x061714, 0x0E231F, 0xDDF3EE, 0x86A8A0, 0x3DD6C3, 0xFF7B6B, 0x1A3530))),
    CONTRAST("contrast", "Контраст", Palette(
        Swatch.of(0xFFFFFF, 0xFFFFFF, 0x000000, 0x3A3A3C, 0x0040DD, 0xD70015, 0xC7C7CC),
        Swatch.of(0x000000, 0x121212, 0xFFFFFF, 0xD1D1D6, 0xFFD60A, 0xFF453A, 0x48484A)));

    companion object {
        fun from(key: String) = entries.firstOrNull { it.key == key } ?: SAND
    }
}

enum class Appearance(val key: String, val title: String) {
    SYSTEM("system", "Как в системе"), LIGHT("light", "Светлое"), DARK("dark", "Тёмное");

    fun isDark(systemDark: Boolean) = when (this) { SYSTEM -> systemDark; LIGHT -> false; DARK -> true }

    companion object {
        fun from(key: String) = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}
