package com.botglobal.mobile.platform.appearance

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppearancePreference {
    System,
    Light,
    Dark,
}

enum class ResolvedAppearance {
    Light,
    Dark,
}

data class AppearanceState(
    val preference: AppearancePreference,
    val systemIsDark: Boolean,
) {
    val resolved: ResolvedAppearance
        get() = when (preference) {
            AppearancePreference.System -> if (systemIsDark) ResolvedAppearance.Dark else ResolvedAppearance.Light
            AppearancePreference.Light -> ResolvedAppearance.Light
            AppearancePreference.Dark -> ResolvedAppearance.Dark
        }
}

class AppearanceController(
    initialPreference: AppearancePreference = AppearancePreference.System,
    initialSystemIsDark: Boolean = false,
) {
    private val mutableState = MutableStateFlow(AppearanceState(initialPreference, initialSystemIsDark))
    val state: StateFlow<AppearanceState> = mutableState.asStateFlow()

    fun select(preference: AppearancePreference) {
        mutableState.value = mutableState.value.copy(preference = preference)
    }

    fun updateSystemAppearance(isDark: Boolean) {
        mutableState.value = mutableState.value.copy(systemIsDark = isDark)
    }
}
