package com.enpo.connect.app.state

import com.botglobal.mobile.platform.appearance.AppearanceController
import com.botglobal.mobile.platform.appearance.AppearancePreference
import com.botglobal.mobile.platform.localization.LocaleController
import com.botglobal.mobile.platform.navigation.BackStackNavigator
import com.botglobal.mobile.platform.preferences.InMemoryPreferenceStore
import com.botglobal.mobile.platform.preferences.PreferenceStore
import com.botglobal.mobile.platform.startup.StartupOrchestrator
import com.botglobal.mobile.platform.startup.StartupStage
import com.botglobal.mobile.platform.startup.StartupStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class EnpoDestination {
    Home,
    Settings,
    Language,
    Theme,
    About,
}

enum class EnpoBootstrapState {
    Starting,
    Ready,
    Failed,
}

class EnpoAppState(
    private val preferences: PreferenceStore = InMemoryPreferenceStore(),
) {
    val locale = LocaleController(restoredLanguageTag())
    val appearance = AppearanceController(initialPreference = restoredAppearance())
    val navigation = BackStackNavigator(EnpoDestination.Home)

    private val mutableBootstrapState = MutableStateFlow(EnpoBootstrapState.Starting)
    val bootstrapState: StateFlow<EnpoBootstrapState> = mutableBootstrapState.asStateFlow()

    private var bootstrapComplete = false

    suspend fun bootstrap() {
        if (bootstrapComplete) return
        val result = StartupOrchestrator(
            listOf(
                StartupStep(StartupStage.PlatformInitialization, critical = true) {},
                StartupStep(StartupStage.Localization, critical = true) {},
                StartupStep(StartupStage.Navigation, critical = true) {
                    navigation.reset(EnpoDestination.Home)
                },
            ),
        ).run()
        bootstrapComplete = result.canNavigate
        mutableBootstrapState.value = if (result.canNavigate) {
            EnpoBootstrapState.Ready
        } else {
            EnpoBootstrapState.Failed
        }
    }

    fun open(destination: EnpoDestination) {
        require(destination != EnpoDestination.Home) { "Home is the navigation root." }
        navigation.push(destination)
    }

    fun navigateBack(): Boolean = navigation.navigateBack()

    fun selectLanguage(languageTag: String) {
        require(languageTag == ArabicLanguageTag || languageTag == EnglishLanguageTag)
        locale.selectLanguage(languageTag)
        preferences.putString(
            EnpoLegacyStorageCompatibility.LanguagePreferenceKey,
            if (languageTag == ArabicLanguageTag) LegacyArabic else LegacyEnglish,
        )
    }

    fun selectAppearance(preference: AppearancePreference) {
        appearance.select(preference)
        preferences.putString(
            EnpoLegacyStorageCompatibility.ThemePreferenceKey,
            preference.toLegacyValue(),
        )
    }

    private fun restoredLanguageTag(): String =
        when (preferences.string(EnpoLegacyStorageCompatibility.LanguagePreferenceKey)) {
            LegacyEnglish -> EnglishLanguageTag
            else -> ArabicLanguageTag
        }

    private fun restoredAppearance(): AppearancePreference =
        when (preferences.string(EnpoLegacyStorageCompatibility.ThemePreferenceKey)) {
            LegacySystem -> AppearancePreference.System
            LegacyDark -> AppearancePreference.Dark
            else -> AppearancePreference.Light
        }

    private fun AppearancePreference.toLegacyValue(): String = when (this) {
        AppearancePreference.System -> LegacySystem
        AppearancePreference.Light -> LegacyLight
        AppearancePreference.Dark -> LegacyDark
    }

    companion object {
        const val ArabicLanguageTag = "ar"
        const val EnglishLanguageTag = "en"

        private const val LegacyArabic = "ARABIC"
        private const val LegacyEnglish = "ENGLISH"
        private const val LegacySystem = "SYSTEM"
        private const val LegacyLight = "LIGHT"
        private const val LegacyDark = "DARK"
    }
}
