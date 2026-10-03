package com.enpo.connect.app.state

import com.botglobal.mobile.platform.appearance.AppearancePreference
import com.botglobal.mobile.platform.appearance.ResolvedAppearance
import com.botglobal.mobile.platform.localization.ContentDirection
import com.botglobal.mobile.platform.device.InstallationId
import com.botglobal.mobile.platform.device.InstallationIdentity
import com.botglobal.mobile.platform.device.PermissionController
import com.botglobal.mobile.platform.device.PermissionKind
import com.botglobal.mobile.platform.device.PermissionState
import com.botglobal.mobile.platform.device.PreferenceInstallationIdStore
import com.botglobal.mobile.platform.invitations.QrScanResult
import com.botglobal.mobile.platform.invitations.QrScannerCapability
import com.botglobal.mobile.platform.notifications.InMemoryMobileDeviceCredentialVault
import com.botglobal.mobile.platform.notifications.MobileDeviceCredential
import com.botglobal.mobile.platform.preferences.InMemoryPreferenceStore
import com.botglobal.mobile.platform.reviews.ReviewCoordinator
import com.botglobal.mobile.platform.reviews.ReviewLaunchOutcome
import com.botglobal.mobile.platform.reviews.ReviewPolicy
import com.botglobal.mobile.platform.reviews.ReviewPreLaunchDecision
import com.botglobal.mobile.platform.reviews.ReviewPromptLauncher
import com.enpo.connect.app.network.EnpoNetworkConfiguration
import com.botglobal.mobile.platform.networking.NetworkEnvironment
import com.enpo.connect.app.pairing.EnpoPairingState
import com.enpo.connect.app.pairing.EnpoPairingClaimResult
import com.enpo.connect.app.pairing.EnpoPairingClient
import com.enpo.connect.app.pairing.EnpoPairingCoordinator
import com.enpo.connect.app.notifications.EnpoNotificationSound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent

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
    fun bootstrapMakesPairingTheRootOnlyWhenNoCredentialExists() = runTest {
        val state = EnpoAppState()

        assertEquals(EnpoBootstrapState.Initializing, state.bootstrapState.value)
        state.bootstrap()

        assertEquals(EnpoBootstrapState.Unpaired, state.bootstrapState.value)
        assertEquals(EnpoDestination.Pairing, state.navigation.current)
        assertFalse(state.navigation.canNavigateBack)
        assertTrue(EnpoMigrationBoundaries.PairingEnabled)
        assertEquals(EnpoPairingState.Unpaired, state.pairingState.value)
    }

    @Test
    fun androidBackContractReturnsThroughTheSharedNavigator() = runTest {
        val state = EnpoAppState(
            deviceInfrastructure = EnpoDeviceInfrastructure {
                EnpoDeviceBootstrapResult.DeviceCredentialAvailable
            },
        )
        state.bootstrap()
        state.open(EnpoDestination.Settings)
        state.open(EnpoDestination.Theme)

        assertTrue(state.navigateBack())
        assertEquals(EnpoDestination.Settings, state.navigation.current)
        assertTrue(state.navigateBack())
        assertEquals(EnpoDestination.Home, state.navigation.current)
        assertFalse(state.navigateBack())
    }

    @Test
    fun bootstrapReflectsCompatibleCredentialAvailabilityWithoutNetworkPairing() = runTest {
        var inspections = 0
        val configuration = EnpoNetworkConfiguration.from(
            "https://botglobalservice.com/backend",
            NetworkEnvironment.Production,
        )
        val state = EnpoAppState(
            deviceInfrastructure = EnpoDeviceInfrastructure {
                inspections += 1
                EnpoDeviceBootstrapResult.DeviceCredentialAvailable
            },
            networkConfiguration = configuration,
        )

        state.bootstrap()

        assertEquals(1, inspections)
        assertEquals(EnpoBootstrapState.DeviceCredentialAvailable, state.bootstrapState.value)
        assertEquals(EnpoDestination.Home, state.navigation.current)
        assertEquals(EnpoPairingState.Paired, state.pairingState.value)
        assertEquals(configuration, state.networkConfiguration)
        assertFalse(EnpoMigrationBoundaries.NetworkCallsDuringUiBootstrap)
    }

    @Test
    fun pairedShellKeepsProfileNotificationsAndSettingsReachable() = runTest {
        val state = EnpoAppState(
            deviceInfrastructure = EnpoDeviceInfrastructure {
                EnpoDeviceBootstrapResult.DeviceCredentialAvailable
            },
        )
        state.bootstrap()

        listOf(
            EnpoDestination.Profile,
            EnpoDestination.Notifications,
            EnpoDestination.Settings,
        ).forEach { destination ->
            state.openPairedDestination(destination)
            assertEquals(destination, state.navigation.current)
            assertTrue(state.navigation.canNavigateBack)
            assertTrue(state.navigateBack())
            assertEquals(EnpoDestination.Home, state.navigation.current)
        }
        state.openPairedDestination(EnpoDestination.Home)
        assertEquals(EnpoDestination.Home, state.navigation.current)
        assertFalse(state.navigation.canNavigateBack)
    }

    @Test
    fun unpairedStateCannotOpenTheAuthenticatedProductShell() = runTest {
        val state = EnpoAppState()
        state.bootstrap()

        assertFailsWith<IllegalArgumentException> {
            state.openPairedDestination(EnpoDestination.Notifications)
        }
        assertEquals(EnpoDestination.Pairing, state.navigation.current)
    }

    @Test
    fun unpairedPairingActionInvokesTheQrScanner() = runTest {
        var scanCount = 0
        val coordinator = EnpoPairingCoordinator(
            permissions = GrantedCameraPermission,
            scanner = object : QrScannerCapability {
                override suspend fun scan(prompt: String): QrScanResult {
                    scanCount += 1
                    return QrScanResult.Recognized("A".repeat(43))
                }
            },
            client = EnpoPairingClient {
                EnpoPairingClaimResult.Success(MobileDeviceCredential("device", "credential"))
            },
            credentialVault = InMemoryMobileDeviceCredentialVault(),
        )
        val state = EnpoAppState(pairingCoordinator = coordinator)
        state.bootstrap()

        state.startPairing("scan")

        assertEquals(1, scanCount)
        assertEquals(EnpoDestination.PairingSuccess, state.navigation.current)
    }

    @Test
    fun notificationPreferencesUseLegacyKeysAndSixEnpoSoundChoices() {
        val preferences = InMemoryPreferenceStore()
        val state = EnpoAppState(preferences)

        state.setNotificationsEnabled(false)
        state.selectNotificationSound(EnpoNotificationSound.Chime)

        assertFalse(state.notificationsEnabled.value)
        assertEquals(EnpoNotificationSound.Chime, state.notificationSound.value)
        assertEquals(false, preferences.boolean(EnpoLegacyStorageCompatibility.NotificationsEnabledPreferenceKey))
        assertEquals("chime", preferences.string(EnpoLegacyStorageCompatibility.NotificationSoundPreferenceKey))
        assertEquals("", preferences.string(EnpoLegacyStorageCompatibility.DeviceNotificationSoundUriPreferenceKey))
        assertEquals(6, EnpoNotificationSound.entries.size)
    }

    @Test
    fun bootstrapPreservesExistingAppProtectionPreference() = runTest {
        val preferences = InMemoryPreferenceStore(
            initialBooleanValues = mapOf(
                EnpoLegacyStorageCompatibility.AppProtectionPreferenceKey to true,
            ),
        )

        EnpoAppState(preferences).bootstrap()

        assertEquals(
            true,
            preferences.boolean(EnpoLegacyStorageCompatibility.AppProtectionPreferenceKey),
        )
    }

    @Test
    fun durablePairingNavigatesThroughSuccessAndSurvivesProcessRecreation() = runTest {
        val vault = InMemoryMobileDeviceCredentialVault()
        val coordinator = EnpoPairingCoordinator(
            permissions = GrantedCameraPermission,
            scanner = FixedScanner(QrScanResult.Recognized("A".repeat(43))),
            client = EnpoPairingClient {
                EnpoPairingClaimResult.Success(
                    MobileDeviceCredential("test-device", "test-credential"),
                )
            },
            credentialVault = vault,
        )
        val firstProcess = EnpoAppState(pairingCoordinator = coordinator)
        firstProcess.bootstrap()

        firstProcess.startPairing("scan")

        assertEquals(EnpoDestination.PairingSuccess, firstProcess.navigation.current)
        firstProcess.enterPairedShell()
        assertEquals(EnpoDestination.Home, firstProcess.navigation.current)

        val installationIdentity = InstallationIdentity(
            PreferenceInstallationIdStore(InMemoryPreferenceStore(), "installation_id"),
        ) { InstallationId("installation") }
        val restarted = EnpoAppState(
            deviceInfrastructure = PlatformEnpoDeviceInfrastructure(installationIdentity, vault),
            pairingCoordinator = EnpoPairingCoordinator(credentialVault = vault),
        )

        restarted.bootstrap()

        assertEquals(EnpoBootstrapState.DeviceCredentialAvailable, restarted.bootstrapState.value)
        assertEquals(EnpoPairingState.Paired, restarted.pairingState.value)
        assertEquals(EnpoDestination.Home, restarted.navigation.current)
        assertFalse(restarted.navigation.canNavigateBack)
    }

    @Test
    fun failed_review_cannot_abort_successful_pairing_or_paired_shell() = runTest {
        val vault = InMemoryMobileDeviceCredentialVault()
        val coordinator = EnpoPairingCoordinator(
            permissions = GrantedCameraPermission,
            scanner = FixedScanner(QrScanResult.Recognized("A".repeat(43))),
            client = EnpoPairingClient {
                EnpoPairingClaimResult.Success(MobileDeviceCredential("device", "credential"))
            },
            credentialVault = vault,
        )
        val reviews = ReviewCoordinator(
            InMemoryPreferenceStore(),
            "reviews",
            ReviewPolicy(firstUseAgeMillis = 0, minimumMeaningfulEvents = 1, minimumMeaningfulEventSpanMillis = 0),
            FailingReviewLauncher,
        ) { 0L }
        val state = EnpoAppState(pairingCoordinator = coordinator, reviews = reviews)
        state.bootstrap()

        state.startPairing("scan")
        state.enterPairedShell()

        assertEquals(EnpoPairingState.Paired, state.pairingState.value)
        assertEquals(EnpoBootstrapState.DeviceCredentialAvailable, state.bootstrapState.value)
        assertEquals(EnpoDestination.Home, state.navigation.current)
    }

    @Test
    fun pairing_first_use_age_persists_and_retries_after_restart_foreground() = runTest {
        var now = 0L
        val reviewStore = InMemoryPreferenceStore()
        val firstLauncher = CountingReviewLauncher()
        val vault = InMemoryMobileDeviceCredentialVault()
        val coordinator = EnpoPairingCoordinator(
            permissions = GrantedCameraPermission,
            scanner = FixedScanner(QrScanResult.Recognized("A".repeat(43))),
            client = EnpoPairingClient {
                EnpoPairingClaimResult.Success(MobileDeviceCredential("device", "credential"))
            },
            credentialVault = vault,
        )
        val first = EnpoAppState(
            pairingCoordinator = coordinator,
            reviews = ReviewCoordinator(
                reviewStore,
                "reviews",
                ReviewPolicy(firstUseAgeMillis = 3L * 24L * 60L * 60L * 1_000L, minimumMeaningfulEvents = 1, minimumMeaningfulEventSpanMillis = 0),
                firstLauncher,
            ) { now },
            reviewScope = backgroundScope,
        )
        first.bootstrap()
        first.startPairing("scan")
        first.enterPairedShell()
        runCurrent()
        assertEquals(0, firstLauncher.launches)

        now = 3L * 24L * 60L * 60L * 1_000L + 1
        val restartedLauncher = CountingReviewLauncher()
        val installationIdentity = InstallationIdentity(
            PreferenceInstallationIdStore(InMemoryPreferenceStore(), "installation_id"),
        ) { InstallationId("installation") }
        val restarted = EnpoAppState(
            deviceInfrastructure = PlatformEnpoDeviceInfrastructure(installationIdentity, vault),
            pairingCoordinator = EnpoPairingCoordinator(credentialVault = vault),
            reviews = ReviewCoordinator(
                reviewStore,
                "reviews",
                ReviewPolicy(firstUseAgeMillis = 3L * 24L * 60L * 60L * 1_000L, minimumMeaningfulEvents = 1, minimumMeaningfulEventSpanMillis = 0),
                restartedLauncher,
            ) { now },
            reviewScope = backgroundScope + UnconfinedTestDispatcher(testScheduler),
        )

        restarted.bootstrap()
        restarted.onForeground()
        runCurrent()

        assertEquals(EnpoDestination.Home, restarted.navigation.current)
        assertEquals(1, restartedLauncher.launches)
    }

    @Test
    fun unreadableLegacyCredentialNeverOffersPairingOrOverwritesState() = runTest {
        val state = EnpoAppState(
            deviceInfrastructure = EnpoDeviceInfrastructure {
                EnpoDeviceBootstrapResult.CredentialUnreadable
            },
        )

        state.bootstrap()
        state.startPairing("scan")

        assertEquals(EnpoBootstrapState.CredentialUnreadable, state.bootstrapState.value)
        assertEquals(EnpoDestination.Pairing, state.navigation.current)
        assertEquals(
            EnpoPairingState.FatalError(com.enpo.connect.app.pairing.EnpoPairingError.CredentialUnreadable),
            state.pairingState.value,
        )
    }

    private object GrantedCameraPermission : PermissionController {
        override suspend fun state(permission: PermissionKind) = PermissionState.Granted
        override suspend fun requestAfterExplanation(permission: PermissionKind) = PermissionState.Granted
    }

    private class FixedScanner(private val result: QrScanResult) : QrScannerCapability {
        override suspend fun scan(prompt: String): QrScanResult = result
    }

    private object FailingReviewLauncher : ReviewPromptLauncher {
        override suspend fun requestReview(beforeNativeLaunch: suspend () -> ReviewPreLaunchDecision): ReviewLaunchOutcome {
            assertEquals(ReviewPreLaunchDecision.Proceed, beforeNativeLaunch())
            return ReviewLaunchOutcome.Failed
        }
    }

    private class CountingReviewLauncher : ReviewPromptLauncher {
        var launches = 0
        override suspend fun requestReview(beforeNativeLaunch: suspend () -> ReviewPreLaunchDecision): ReviewLaunchOutcome =
            when (beforeNativeLaunch()) {
                ReviewPreLaunchDecision.Proceed -> {
                    launches++
                    ReviewLaunchOutcome.Launched
                }
                ReviewPreLaunchDecision.Deferred -> ReviewLaunchOutcome.Deferred
                ReviewPreLaunchDecision.Failed -> ReviewLaunchOutcome.Failed
            }
    }
}
