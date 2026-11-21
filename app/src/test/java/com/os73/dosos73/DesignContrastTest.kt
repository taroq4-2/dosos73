package com.os73.dosos73

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG 2.x contrast for the dosos73 palettes (AA: 4.5:1 normal text, 3:1 large text and UI parts). */
class DesignContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val first = a.luminance().toDouble()
        val second = b.luminance().toDouble()
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }

    private fun check(name: String, a: Color, b: Color, minimum: Double) {
        val ratio = contrast(a, b)
        assertTrue("$name = ${"%.2f".format(ratio)} < $minimum", ratio >= minimum)
    }

    @Test
    fun bothThemesMeetTextContrast() {
        for ((theme, p) in listOf("dark" to DososPalettes.Dark, "light" to DososPalettes.Light)) {
            for ((surfaceName, surface) in listOf("background" to p.background, "surface" to p.surface, "raised" to p.raised)) {
                check("$theme text/$surfaceName", p.text, surface, 4.5)
                check("$theme secondary/$surfaceName", p.secondary, surface, 4.5)
                check("$theme muted/$surfaceName", p.muted, surface, 4.5)
                check("$theme accent/$surfaceName", p.accent, surface, 3.0) // icons, selected nav, borders
            }
            check("$theme button label", p.onAccent, p.accent, 4.5)
            check("$theme error/surface", p.error, p.surface, 4.5)
            check("$theme nav label on indicator", p.accent, p.accentSoft, 3.0)
        }
    }

    @Test
    fun referenceIdentityIsTheDarkPalette() {
        // Tokens requested in the design reference (DOSOS73_MASTER_PROMPT_AR.md section 8).
        assertTrue(DososPalettes.Dark.background == Color(0xFF0B1929))
        assertTrue(DososPalettes.Dark.surface == Color(0xFF14273A))
        assertTrue(DososPalettes.Dark.accent == Color(0xFF42D9CC))
        assertTrue(DososPalettes.Dark.text == Color(0xFFE4F5F4))
    }
}
