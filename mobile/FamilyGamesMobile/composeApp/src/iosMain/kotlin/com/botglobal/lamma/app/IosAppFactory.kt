package com.botglobal.lamma.app

import androidx.compose.ui.window.ComposeUIViewController
import com.botglobal.lamma.app.state.AppLanguage
import com.botglobal.lamma.app.state.ApplicationLanguagePreferences
import com.botglobal.lamma.app.state.appLanguageFromPreference
import com.botglobal.lamma.app.state.preferenceValue
import com.botglobal.lamma.app.ui.FamilyGamesApp
import com.botglobal.mobile.platform.device.SemanticHaptics
import com.botglobal.mobile.platform.identity.SessionVault
import platform.Foundation.NSUserDefaults

private class IosApplicationLanguagePreferences(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : ApplicationLanguagePreferences {
    override fun restore(): AppLanguage? = appLanguageFromPreference(defaults.stringForKey(KEY_LANGUAGE))

    override fun save(language: AppLanguage) {
        defaults.setObject(language.preferenceValue(), forKey = KEY_LANGUAGE)
    }

    private companion object {
        const val KEY_LANGUAGE = "botglobal.familygames.applicationLanguage"
    }
}

fun MainViewController(
    apiBaseUrl: String,
    sessionVault: SessionVault,
    haptics: SemanticHaptics,
    appVersion: String,
) = ComposeUIViewController {
    FamilyGamesApp(
        apiBaseUrl = apiBaseUrl,
        sessionVault = sessionVault,
        haptics = haptics,
        appVersion = appVersion,
        platform = "ios",
        languagePreferences = IosApplicationLanguagePreferences(),
    )
}
