package com.privee.app

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.content.edit
import java.util.Locale

enum class AppLanguage(val tag: String, val label: String) {
    English("en", "English"),
    Italian("it", "Italiano"),
    Portuguese("pt-PT", "Português (Portugal)"),
    Spanish("es", "Español"),
    French("fr", "Français");

    companion object {
        fun fromTag(tag: String?): AppLanguage? = entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) }
    }
}

/** Independent of account/server storage, including logout and forgetting encryption keys. */
class LanguagePreferences(private val context: Context) {
    private val preferences = context.getSharedPreferences("language", Context.MODE_PRIVATE)

    val language: AppLanguage
        get() {
            if (Build.VERSION.SDK_INT >= 33) {
                val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
                AppLanguage.fromTag(locales.get(0)?.toLanguageTag())?.let { return it }
            }
            return AppLanguage.fromTag(preferences.getString("selected", null)) ?: AppLanguage.English
        }

    fun initialize() {
        val selected = language
        preferences.edit { putString("selected", selected.tag) }
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(LocaleManager::class.java)
            val locales = LocaleList.forLanguageTags(selected.tag)
            if (manager.applicationLocales != locales) manager.applicationLocales = locales
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(selected.tag))
        }
    }

    fun select(language: AppLanguage) {
        preferences.edit { putString("selected", language.tag) }
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.tag)
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
        }
    }

    // Application and push-service contexts do not inherit AppCompat's pre-33 activity locale.
    fun localizedContext(): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(Locale.forLanguageTag(language.tag)))
        return context.createConfigurationContext(configuration)
    }
}

fun Context.localizedContext(): Context =
    (applicationContext as PriveeApplication).languages.localizedContext()
