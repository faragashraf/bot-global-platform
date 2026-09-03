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
import com.enpo.connect.app.network.EnpoNetworkConfiguration
import com.enpo.connect.app.notifications.EnpoNotificationSound
import com.enpo.connect.app.pairing.EnpoPairingCoordinator
import com.enpo.connect.app.pairing.EnpoPairingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class EnpoDestination {
    Pairing,
    PairingSuccess,
    Home,
    Notifications,
    NotificationSettings,
    Profile,
    Settings,
    Language,
    Theme,
    DeviceStatus,
    PairingInfo,
    About,
}

sealed interface EnpoBootstrapState {
    data object Initializing : EnpoBootstrapState
    data object Unpaired : EnpoBootstrapState
    data object DeviceCredentialAvailable : EnpoBootstrapState
    data object CredentialUnreadable : EnpoBootstrapState
    data object Error : EnpoBootstrapState
}

class EnpoAppState(
    private val preferences: PreferenceStore = InMemoryPreferenceStore(),
    private val deviceInfrastructure: EnpoDeviceInfrastructure = EmptyEnpoDeviceInfrastructure,
    val networkConfiguration: EnpoNetworkConfiguration? = null,
    private val pairingCoordinator: EnpoPairingCoordinator = EnpoPairingCoordinator(),
) {
    val locale = LocaleController(restoredLanguageTag())
    val appearance = AppearanceController(initialPreference = restoredAppearance())
    val navigation = BackStackNavigator(EnpoDestination.Home)
    val pairingState: StateFlow<EnpoPairingState> = pairingCoordinator.state

    private val mutableBootstrapState = MutableStateFlow<EnpoBootstrapState>(EnpoBootstrapState.Initializing)
    val bootstrapState: StateFlow<EnpoBootstrapState> = mutableBootstrapState.asStateFlow()

    private var bootstrapComplete = false
    private val mutableSelectedNotificationId = MutableStateFlow<String?>(null)
    val selectedNotificationId: StateFlow<String?> = mutableSelectedNotificationId.asStateFlow()
    private val mutableNotificationsEnabled = MutableStateFlow(
        preferences.boolean(EnpoLegacyStorageCompatibility.NotificationsEnabledPreferenceKey) != false,
    )
    val notificationsEnabled: StateFlow<Boolean> = mutableNotificationsEnabled.asStateFlow()
    private val mutableNotificationSound = MutableStateFlow(
        EnpoNotificationSound.fromStorage(
            preferences.string(EnpoLegacyStorageCompatibility.NotificationSoundPreferenceKey),
        ),
    )
    val notificationSound: StateFlow<EnpoNotificationSound> = mutableNotificationSound.asStateFlow()

    suspend fun bootstrap() {
        if (bootstrapComplete) return
        var deviceResult: EnpoDeviceBootstrapResult? = null
        val result = StartupOrchestrator(
            listOf(
                StartupStep(StartupStage.PlatformInitialization, critical = true) {},
                StartupStep(StartupStage.Localization, critical = true) {},
                StartupStep(StartupStage.SessionRestoration, critical = true) {
                    deviceResult = deviceInfrastructure.inspect()
                },
                StartupStep(StartupStage.Navigation, critical = true) {
                    navigation.reset(EnpoDestination.Home)
                },
            ),
        ).run()
        bootstrapComplete = true
        mutableBootstrapState.value = if (!result.canNavigate) {
            EnpoBootstrapState.Error
        } else when (deviceResult) {
            EnpoDeviceBootstrapResult.Unpaired -> {
                pairingCoordinator.initializeUnpaired()
                navigation.reset(EnpoDestination.Pairing)
                EnpoBootstrapState.Unpaired
            }
            EnpoDeviceBootstrapResult.DeviceCredentialAvailable -> {
                pairingCoordinator.initializePaired()
                navigation.reset(EnpoDestination.Home)
                EnpoBootstrapState.DeviceCredentialAvailable
            }
            EnpoDeviceBootstrapResult.CredentialUnreadable -> {
                pairingCoordinator.initializeCredentialUnreadable()
                navigation.reset(EnpoDestination.Pairing)
                EnpoBootstrapState.CredentialUnreadable
            }
            null -> EnpoBootstrapState.Error
        }
    }

    suspend fun startPairing(scannerPrompt: String) {
        pairingCoordinator.startPairing(scannerPrompt)
        if (pairingCoordinator.state.value == EnpoPairingState.Paired) {
            mutableBootstrapState.value = EnpoBootstrapState.DeviceCredentialAvailable
            navigation.reset(EnpoDestination.PairingSuccess)
        }
    }

    fun enterPairedShell() {
        if (pairingCoordinator.state.value == EnpoPairingState.Paired) {
            navigation.reset(EnpoDestination.Home)
        }
    }

    fun open(destination: EnpoDestination) {
        require(destination !in RootDestinations) { "Root destinations cannot be pushed." }
        navigation.push(destination)
    }

    fun openNotifications() {
        mutableSelectedNotificationId.value = null
        open(EnpoDestination.Notifications)
    }

    fun openPairedDestination(destination: EnpoDestination) {
        require(destination in PairedDestinations) { "Destination is not part of the paired shell." }
        require(bootstrapState.value == EnpoBootstrapState.DeviceCredentialAvailable) {
            "Paired destinations require a readable device credential."
        }
        mutableSelectedNotificationId.value = null
        if (navigation.current != destination) {
            if (destination == EnpoDestination.Home) navigation.reset(destination)
            else navigation.push(destination)
        }
    }

    fun openNotification(id: String) {
        mutableSelectedNotificationId.value = id
        if (navigation.current != EnpoDestination.Notifications) {
            open(EnpoDestination.Notifications)
        }
    }

    fun closeNotificationDetail() {
        mutableSelectedNotificationId.value = null
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

    fun setNotificationsEnabled(enabled: Boolean) {
        mutableNotificationsEnabled.value = enabled
        preferences.putBoolean(EnpoLegacyStorageCompatibility.NotificationsEnabledPreferenceKey, enabled)
    }

    fun selectNotificationSound(sound: EnpoNotificationSound) {
        mutableNotificationSound.value = sound
        preferences.putString(EnpoLegacyStorageCompatibility.NotificationSoundPreferenceKey, sound.storageKey)
        preferences.putString(EnpoLegacyStorageCompatibility.DeviceNotificationSoundUriPreferenceKey, "")
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
        private val RootDestinations = setOf(
            EnpoDestination.Home,
            EnpoDestination.Pairing,
            EnpoDestination.PairingSuccess,
        )
        private val PairedDestinations = setOf(
            EnpoDestination.Home,
            EnpoDestination.Notifications,
            EnpoDestination.Profile,
            EnpoDestination.Settings,
        )
    }
}
