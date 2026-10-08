package com.privee.app

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Accent colour of the app theme; palettes live in `ui/Theme.kt`. */
enum class AppAccent(val key: String, @param:StringRes val label: Int) {
    Lilac("lilac", R.string.accent_lilac),
    Blue("blue", R.string.accent_blue),
    Teal("teal", R.string.accent_teal),
    Green("green", R.string.accent_green),
    Amber("amber", R.string.accent_amber),
    Orange("orange", R.string.accent_orange),
    Red("red", R.string.accent_red),
    Pink("pink", R.string.accent_pink);

    companion object {
        val Default = Lilac

        fun fromKey(key: String?): AppAccent = entries.firstOrNull { it.key == key } ?: Default
    }
}

/**
 * Appearance choices, kept on this device only: never sent to a server, kept across logout and
 * forgetting the device, and excluded from backups (`data_extraction_rules.xml`).
 */
class ThemePreferences(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("appearance", Context.MODE_PRIVATE))

    private val selected = MutableStateFlow(AppAccent.fromKey(preferences.getString(ACCENT, null)))
    val accent: StateFlow<AppAccent> = selected.asStateFlow()

    fun select(accent: AppAccent) {
        preferences.edit { putString(ACCENT, accent.key) }
        selected.value = accent
    }

    private companion object {
        const val ACCENT = "accent"
    }
}
