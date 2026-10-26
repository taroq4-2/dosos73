package com.os73.dosos73

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val first = a.luminance().toDouble()
        val second = b.luminance().toDouble()
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }

    @Test
    fun actionTextAndSecondaryCopyMeetNormalTextContrast() {
        assertTrue(contrast(Color.White, Neon.ActionBlue) >= 4.5)
        assertTrue(contrast(Color.White, Neon.Purple) >= 4.5)
        assertTrue(contrast(Neon.Secondary, Neon.Surface) >= 4.5)
        assertTrue(contrast(Neon.Muted, Neon.Background) >= 4.5)
    }
}
