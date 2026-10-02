package com.botglobal.mobile.platform.appearance

import kotlin.test.Test
import kotlin.test.assertEquals

class AppearanceControllerTests {
    @Test
    fun systemPreferenceFollowsSystemAppearanceChanges() {
        val controller = AppearanceController(
            initialPreference = AppearancePreference.System,
            initialSystemIsDark = false,
        )

        assertEquals(ResolvedAppearance.Light, controller.state.value.resolved)

        controller.updateSystemAppearance(isDark = true)

        assertEquals(ResolvedAppearance.Dark, controller.state.value.resolved)
    }

    @Test
    fun explicitPreferencesIgnoreSystemAppearance() {
        val controller = AppearanceController(initialSystemIsDark = true)

        controller.select(AppearancePreference.Light)
        assertEquals(ResolvedAppearance.Light, controller.state.value.resolved)

        controller.updateSystemAppearance(isDark = false)
        controller.select(AppearancePreference.Dark)
        assertEquals(ResolvedAppearance.Dark, controller.state.value.resolved)
    }

    @Test
    fun selected_preference_is_reported_for_persistent_storage() {
        val selected = mutableListOf<AppearancePreference>()
        val controller = AppearanceController(onPreferenceSelected = selected::add)

        controller.select(AppearancePreference.Light)
        controller.updateSystemAppearance(isDark = true)

        assertEquals(listOf(AppearancePreference.Light), selected)
        assertEquals(AppearancePreference.Light, controller.state.value.preference)
    }
}
