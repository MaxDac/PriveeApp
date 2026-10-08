package com.privee.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemePreferencesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val stored get() = app.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    @Before
    fun clear() {
        stored.edit().clear().commit()
    }

    @Test
    fun `lilac is the default`() {
        assertEquals(AppAccent.Lilac, ThemePreferences(app).accent.value)
    }

    @Test
    fun `the selection is published and kept on the device`() {
        val themes = ThemePreferences(app)
        themes.select(AppAccent.Teal)
        assertEquals(AppAccent.Teal, themes.accent.value)
        assertEquals("teal", stored.getString("accent", null))
        assertEquals(AppAccent.Teal, ThemePreferences(app).accent.value)
    }

    @Test
    fun `unknown stored values fall back to lilac`() {
        stored.edit().putString("accent", "ultraviolet").commit()
        assertEquals(AppAccent.Lilac, ThemePreferences(app).accent.value)
    }

    @Test
    fun `at least eight distinct accents with stable keys`() {
        assertEquals(
            listOf("lilac", "blue", "teal", "green", "amber", "orange", "red", "pink"),
            AppAccent.entries.map { it.key },
        )
        assertEquals(AppAccent.entries.size, AppAccent.entries.map { it.label }.toSet().size)
        AppAccent.entries.forEach { assertEquals(it, AppAccent.fromKey(it.key)) }
    }
}
