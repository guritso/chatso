package com.example.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {
    private const val PREFS_NAME = "chatso_locale_prefs"
    private const val KEY_LANG = "app_language"

    fun getSavedLanguage(context: Context): AppLanguage {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AppLanguage.fromCode(prefs.getString(KEY_LANG, AppLanguage.SYSTEM.code))
    }

    fun saveLanguage(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANG, language.code)
            .apply()
    }

    fun wrapContext(base: Context): Context {
        val lang = getSavedLanguage(base)
        if (lang == AppLanguage.SYSTEM) return base
        val locale = Locale.forLanguageTag(lang.code)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
