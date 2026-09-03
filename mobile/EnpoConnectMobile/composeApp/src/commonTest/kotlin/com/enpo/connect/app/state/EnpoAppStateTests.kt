package com.enpo.connect.app.state

import com.botglobal.mobile.platform.appearance.AppearancePreference
import com.botglobal.mobile.platform.appearance.ResolvedAppearance
import com.botglobal.mobile.platform.localization.ContentDirection
import com.botglobal.mobile.platform.preferences.InMemoryPreferenceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class EnpoAppStateTests {
    @Test
    fun defaultsToLegacyArabicRtlAndLightAppearance() {
        val state = EnpoAppState()

        assertEquals("ar", state.locale.state.value.languageTag)
        assertEquals(ContentDirection.RightToLeft, state.locale.state.value.direction)
        assertEquals(AppearancePreference.Light, state.appearance.state.value.preference)
        assertEquals(ResolvedAppearance.Light, state.appearance.state.value.resolved)
    }

    @Test
    fun englishUsesLtrAndPersistsWithTheLegacyValue() {
        val preferences = InMemoryPreferenceStore()
        val state = EnpoAppState(preferences)

        state.selectLanguage("en")

        assertEquals(ContentDirection.LeftToRight, state.locale.state.value.direction)
        assertEquals("ENGLISH", preferences.string(EnpoLegacyStorageCompatibility.LanguagePreferenceKey))
        assertEquals("en", EnpoAppState(preferences).locale.state.value.languageTag)
    }

    @Test
    fun lightDarkAndSystemUseSharedAppearanceAndLegacyPersistenceValues() {
        val preferences = InMemoryPreferenceStore()
        val state = EnpoAppState(preferences)

        val expectations = listOf(
            AppearancePreference.Light to "LIGHT",
            AppearancePreference.Dark to "DARK",
            AppearancePreference.System to "SYSTEM",
        )
        expectations.forEach { (preference, stored) ->
            state.selectAppearance(preference)
            assertEquals(preference, state.appearance.state.value.preference)
            assertEquals(stored, preferences.string(EnpoLegacyStorageCompatibility.ThemePreferenceKey))
        }
    }

    @Test
    fun bootstrapMakesHomeTheRootWithoutFakingPairingState() = runTest {
        val state = EnpoAppState()

        assertEquals(EnpoBootstrapState.Starting, state.bootstrapState.value)
        state.bootstrap()

        assertEquals(EnpoBootstrapState.Ready, state.bootstrapState.value)
        assertEquals(EnpoDestination.Home, state.navigation.current)
        assertFalse(state.navigation.canNavigateBack)
        assertFalse(EnpoSlice1Boundaries.PairingEnabled)
    }

    @Test
    fun androidBackContractReturnsThroughTheSharedNavigator() = runTest {
        val state = EnpoAppState()
        state.bootstrap()
        state.open(EnpoDestination.Settings)
        state.open(EnpoDestination.Theme)

        assertTrue(state.navigateBack())
        assertEquals(EnpoDestination.Settings, state.navigation.current)
        assertTrue(state.navigateBack())
        assertEquals(EnpoDestination.Home, state.navigation.current)
        assertFalse(state.navigateBack())
    }
}
