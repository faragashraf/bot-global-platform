package com.ashraffarag.sentricam.localization.android

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.ashraffarag.sentricam.localization.domain.AppLanguagePreference
import com.ashraffarag.sentricam.localization.domain.AppLocaleApplier

class SharedPreferencesAppLanguagePreference(context: Context) : AppLanguagePreference {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun loadLanguageTag(): String? = preferences.getString(KEY_LANGUAGE_TAG, null)

    override fun saveLanguageTag(languageTag: String) {
        preferences.edit().putString(KEY_LANGUAGE_TAG, languageTag).apply()
    }

    private companion object {
        const val PREFERENCES = "app_language"
        const val KEY_LANGUAGE_TAG = "selected_language_tag"
    }
}

object AndroidAppLocaleApplier : AppLocaleApplier {
    override fun applyLanguageTag(languageTag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
    }
}
