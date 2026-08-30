package com.botglobal.nqrb.app.state

import com.botglobal.mobile.platform.appearance.AppearanceController
import com.botglobal.mobile.platform.localization.LocaleController
import com.botglobal.mobile.platform.navigation.BackStackNavigator

enum class NqrbDestination {
    Home,
    History,
    People,
    Profile,
    Settings,
}

class NqrbAppState(
    val locale: LocaleController = LocaleController(DEFAULT_LANGUAGE),
    val appearance: AppearanceController = AppearanceController(),
    val navigation: BackStackNavigator<NqrbDestination> = BackStackNavigator(NqrbDestination.Home),
) {
    fun openSettings() = navigation.push(NqrbDestination.Settings)

    fun selectTopLevel(destination: NqrbDestination) {
        require(destination != NqrbDestination.Settings) { "Settings is not a top-level destination." }
        navigation.selectTopLevel(destination)
    }

    companion object {
        const val DEFAULT_LANGUAGE = "ar"
    }
}
