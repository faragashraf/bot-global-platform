package com.botglobal.familygames.app.state

interface ApplicationLanguagePreferences {
    fun restore(): AppLanguage?
    fun save(language: AppLanguage)
}

object UnavailableApplicationLanguagePreferences : ApplicationLanguagePreferences {
    override fun restore(): AppLanguage? = null
    override fun save(language: AppLanguage) = Unit
}

internal fun AppLanguage.preferenceValue(): String = when (this) {
    AppLanguage.Arabic -> "ar"
    AppLanguage.English -> "en"
}

internal fun appLanguageFromPreference(value: String?): AppLanguage? = when (value) {
    "ar" -> AppLanguage.Arabic
    "en" -> AppLanguage.English
    else -> null
}
