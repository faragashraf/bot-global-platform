package com.enpo.connect.app.state

import com.botglobal.mobile.platform.appearance.AppearancePreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class EnpoStartupPresentationTests {
    @Test
    fun visibleColdLaunchCompletesExactlyOnce() {
        val launch = EnpoVisibleLaunchState()

        assertFalse(launch.isComplete)
        assertTrue(launch.complete())
        assertFalse(launch.complete())
        assertTrue(launch.isComplete)
    }

    @Test
    fun choreographyMatchesTheAuthoritativeLegacyTimingAndOppositeEntrances() {
        assertEquals(3_000L, EnpoStartupAnimationSpec.VisibleLaunchDurationMillis)
        assertEquals(560, EnpoStartupAnimationSpec.LogoTravelDp)
        assertEquals(1, EnpoStartupAnimationSpec.ConnectInitialTranslationMultiplier)
        assertEquals(-1, EnpoStartupAnimationSpec.EgyptPostInitialTranslationMultiplier)
        assertEquals(1_250, EnpoStartupAnimationSpec.LogoTravelDurationMillis)
        assertEquals(520, EnpoStartupAnimationSpec.BrandFadeDurationMillis)
        assertEquals(1_900, EnpoStartupAnimationSpec.BackgroundScaleDurationMillis)
        assertEquals(120L, EnpoStartupAnimationSpec.SecondaryRevealDelayMillis)
        assertEquals(360, EnpoStartupAnimationSpec.DividerFadeDurationMillis)
        assertEquals(520, EnpoStartupAnimationSpec.LoadingFadeDurationMillis)
    }

    @Test
    fun pairedBootstrapKeepsHomeAsTheSingleRootAfterAnimation() = runTest {
        val state = EnpoAppState(
            deviceInfrastructure = EnpoDeviceInfrastructure {
                EnpoDeviceBootstrapResult.DeviceCredentialAvailable
            },
        )
        val launch = EnpoVisibleLaunchState()

        state.bootstrap()
        launch.complete()
        launch.complete()

        assertTrue(launch.isComplete)
        assertEquals(EnpoDestination.Home, state.navigation.current)
        assertEquals(listOf(EnpoDestination.Home), state.navigation.backStack.value)
    }

    @Test
    fun unpairedBootstrapKeepsPairingAsTheSingleRootAfterAnimation() = runTest {
        val state = EnpoAppState()
        val launch = EnpoVisibleLaunchState()

        state.bootstrap()
        launch.complete()

        assertEquals(EnpoDestination.Pairing, state.navigation.current)
        assertEquals(listOf(EnpoDestination.Pairing), state.navigation.backStack.value)
    }

    @Test
    fun backgroundPushBootstrapNeverRequiresVisibleStartupAnimation() {
        assertTrue(
            EnpoStartupRuntimePolicy.requiresVisibleAnimation(
                EnpoStartupContext.VisibleLaunch,
            ),
        )
        assertFalse(
            EnpoStartupRuntimePolicy.requiresVisibleAnimation(
                EnpoStartupContext.BackgroundPushBootstrap,
            ),
        )
    }

    @Test
    fun startupSupportsEveryAppearanceModeWithoutAlternateProductBranding() {
        assertEquals(
            setOf(
                AppearancePreference.Light,
                AppearancePreference.Dark,
                AppearancePreference.System,
            ),
            EnpoStartupAnimationSpec.supportedAppearances,
        )
        assertEquals(setOf("Connect", "Egypt Post"), setOf(
            EnpoStartupBranding.Connect,
            EnpoStartupBranding.EgyptPost,
        ))
    }
}
