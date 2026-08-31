package com.ashraffarag.sentricam.localization.domain

enum class AppLanguage(val languageTag: String) {
    ENGLISH("en"),
    ARABIC("ar"),
    ;

    companion object {
        fun fromLanguageTag(languageTag: String?): AppLanguage? {
            val language = languageTag
                ?.substringBefore('-')
                ?.substringBefore('_')
                ?.lowercase()
            return entries.firstOrNull { it.languageTag == language }
        }
    }
}

interface AppLanguagePreference {
    fun loadLanguageTag(): String?
    fun saveLanguageTag(languageTag: String)
}

fun interface AppLocaleApplier {
    fun applyLanguageTag(languageTag: String)
}

class AppLanguageController(
    private val preference: AppLanguagePreference,
    private val localeApplier: AppLocaleApplier,
) {
    fun selectedLanguage(): AppLanguage? = AppLanguage.fromLanguageTag(preference.loadLanguageTag())

    fun restorePersistedLanguage(): AppLanguage? = selectedLanguage()?.also {
        localeApplier.applyLanguageTag(it.languageTag)
    }

    fun selectLanguage(language: AppLanguage): Boolean {
        if (selectedLanguage() == language) return false
        preference.saveLanguageTag(language.languageTag)
        localeApplier.applyLanguageTag(language.languageTag)
        return true
    }
}
