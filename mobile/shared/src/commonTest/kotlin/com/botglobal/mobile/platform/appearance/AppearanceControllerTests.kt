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
}
