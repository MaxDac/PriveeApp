package com.privee.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.privee.app.AppAccent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThemeTest {
    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    @Test
    fun `lilac keeps the original palette`() {
        for (dark in listOf(false, true)) {
            val scheme = colorSchemeFor(AppAccent.Lilac, dark)
            assertEquals(if (dark) Color(0xFFB4A3FF) else Color(0xFF7C5CFF), scheme.primary)
            assertEquals(if (dark) Color(0xFF0F1020) else Color(0xFFFBF8FF), scheme.background)
        }
    }

    @Test
    fun `every accent changes the primary colours but not secondary or error`() {
        for (dark in listOf(false, true)) {
            val lilac = colorSchemeFor(AppAccent.Lilac, dark)
            val schemes = AppAccent.entries.map { colorSchemeFor(it, dark) }
            assertEquals(AppAccent.entries.size, schemes.map { it.primary }.toSet().size)
            for (scheme in schemes) {
                assertEquals(lilac.secondary, scheme.secondary)
                assertEquals(lilac.error, scheme.error)
                assertEquals(scheme.primary, scheme.surfaceTint)
            }
        }
    }

    @Test
    fun `accent text stays readable`() {
        for (accent in AppAccent.entries) {
            for (dark in listOf(false, true)) {
                val scheme = colorSchemeFor(accent, dark)
                val name = "$accent dark=$dark"
                assertTrue(contrast(scheme.primary, scheme.onPrimary) >= 4.2f, "$name primary")
                assertTrue(contrast(scheme.primaryContainer, scheme.onPrimaryContainer) >= 4.5f, "$name container")
                assertTrue(contrast(scheme.background, scheme.onBackground) >= 4.5f, "$name background")
                assertTrue(contrast(scheme.surfaceVariant, scheme.onSurfaceVariant) >= 4.5f, "$name variant")
                assertTrue(contrast(scheme.background, scheme.primary) >= 3f, "$name primary on background")
            }
        }
    }

    @Test
    fun `neutrals take the accent hue and keep their lightness`() {
        val lilac = colorSchemeFor(AppAccent.Lilac, dark = true)
        val green = colorSchemeFor(AppAccent.Green, dark = true)
        assertNotEquals(lilac.background, green.background)
        assertEquals(lilac.background.luminance(), green.background.luminance(), 0.01f)
        assertEquals(accentSwatch(AppAccent.Green).hue(), green.background.hue(), 2f)
    }

    @Test
    fun `hue conversions round trip`() {
        val violet = Color(0xFF7C5CFF)
        val same = violet.withHue(violet.hue())
        assertEquals(violet.red, same.red, 0.01f)
        assertEquals(violet.green, same.green, 0.01f)
        assertEquals(violet.blue, same.blue, 0.01f)
    }
}
