package com.enpo.connect.app

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.botglobal.mobile.platform.appearance.AppearancePreference
import com.botglobal.mobile.platform.appearance.ResolvedAppearance
import com.botglobal.mobile.platform.localization.ContentDirection
import com.botglobal.mobile.platform.notifications.InMemoryNotificationInbox
import com.botglobal.mobile.platform.notifications.NotificationInbox
import com.botglobal.mobile.platform.notifications.SemanticNotification
import com.botglobal.mobile.platform.notifications.SemanticNotificationDestination
import com.botglobal.mobile.platform.notifications.isSemanticNotificationId
import com.botglobal.mobile.platform.preferences.InMemoryPreferenceStore
import com.botglobal.mobile.platform.preferences.PreferenceStore
import com.botglobal.mobile.platform.profile.ProfileController
import com.botglobal.mobile.platform.profile.ProfileLoadState
import com.botglobal.mobile.platform.profile.ProfileRepository
import com.botglobal.mobile.platform.profile.UnavailableProfileRepository
import com.enpo.connect.app.network.EnpoNetworkConfiguration
import com.enpo.connect.app.notifications.EnpoNotificationActionHandler
import com.enpo.connect.app.notifications.EnpoNotificationPermissionRequester
import com.enpo.connect.app.notifications.EnpoNotificationSound
import com.enpo.connect.app.notifications.NoOpNotificationActionHandler
import com.enpo.connect.app.notifications.NoOpNotificationPermissionRequester
import com.enpo.connect.app.pairing.EnpoPairingCoordinator
import com.enpo.connect.app.pairing.EnpoPairingState
import com.enpo.connect.app.state.EmptyEnpoDeviceInfrastructure
import com.enpo.connect.app.state.EnpoAppState
import com.enpo.connect.app.state.EnpoBootstrapState
import com.enpo.connect.app.state.EnpoDestination
import com.enpo.connect.app.state.EnpoDeviceInfrastructure
import com.enpo.connect.app.state.EnpoStartupAnimationSpec
import com.enpo.connect.app.state.EnpoVisibleLaunchState
import com.enpo.connect.app.state.synchronizeEnpoProfile
import com.enpo.connect.app.ui.EnpoBrandHeader
import com.enpo.connect.app.ui.EnpoNotificationsScreen
import com.enpo.connect.app.ui.EnpoPairedScreen
import com.enpo.connect.app.ui.EnpoPairedTab
import com.enpo.connect.app.ui.EnpoSplash
import com.enpo.connect.app.ui.EnpoStrings
import com.enpo.connect.app.ui.EnpoSystemBackHandler
import com.enpo.connect.app.ui.EnpoTheme
import com.enpo.connect.app.ui.enpoStrings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun EnpoConnectApp(
    runtimeVersionName: String,
    preferences: PreferenceStore = InMemoryPreferenceStore(),
    deviceInfrastructure: EnpoDeviceInfrastructure = EmptyEnpoDeviceInfrastructure,
    networkConfiguration: EnpoNetworkConfiguration? = null,
    pairingCoordinator: EnpoPairingCoordinator = EnpoPairingCoordinator(),
    profileRepository: ProfileRepository = UnavailableProfileRepository,
    notificationInbox: NotificationInbox = InMemoryNotificationInbox(),
    notificationId: String? = null,
    onNotificationHandled: () -> Unit = {},
    notificationPermissionRequester: EnpoNotificationPermissionRequester =
        NoOpNotificationPermissionRequester,
    notificationActionHandler: EnpoNotificationActionHandler = NoOpNotificationActionHandler,
    onPairingCompleted: () -> Unit = {},
    onResolvedAppearanceChanged: (ResolvedAppearance) -> Unit = {},
) {
    val state = remember(preferences, deviceInfrastructure, networkConfiguration, pairingCoordinator) {
        EnpoAppState(preferences, deviceInfrastructure, networkConfiguration, pairingCoordinator)
    }
    val profileController = remember(profileRepository) { ProfileController(profileRepository) }
    val locale by state.locale.state.collectAsState()
    val appearance by state.appearance.state.collectAsState()
    val backStack by state.navigation.backStack.collectAsState()
    val bootstrapState by state.bootstrapState.collectAsState()
    val pairingState by state.pairingState.collectAsState()
    val selectedNotificationId by state.selectedNotificationId.collectAsState()
    val notificationsEnabled by state.notificationsEnabled.collectAsState()
    val notificationSound by state.notificationSound.collectAsState()
    val notifications by notificationInbox.notifications.collectAsState()
    val profileState by profileController.state.collectAsState()
    val scope = rememberCoroutineScope()
    val systemIsDark = isSystemInDarkTheme()
    val visibleLaunch = rememberSaveable(saver = EnpoVisibleLaunchState.Saver) {
        EnpoVisibleLaunchState()
    }

    LaunchedEffect(state) { state.bootstrap() }
    LaunchedEffect(visibleLaunch) {
        if (!visibleLaunch.isComplete) {
            delay(EnpoStartupAnimationSpec.VisibleLaunchDurationMillis)
            visibleLaunch.complete()
        }
    }
    LaunchedEffect(systemIsDark) { state.appearance.updateSystemAppearance(systemIsDark) }
    LaunchedEffect(appearance.resolved, visibleLaunch.isComplete) {
        if (visibleLaunch.isComplete) {
            onResolvedAppearanceChanged(appearance.resolved)
        }
    }
    LaunchedEffect(bootstrapState) {
        synchronizeEnpoProfile(bootstrapState, profileController)
    }
    LaunchedEffect(notificationId, bootstrapState) {
        if (notificationId == null) return@LaunchedEffect
        if (!isSemanticNotificationId(notificationId) ||
            bootstrapState != EnpoBootstrapState.DeviceCredentialAvailable
        ) {
            onNotificationHandled()
            return@LaunchedEffect
        }
        if (notificationInbox.list().any { it.id == notificationId }) {
            notificationInbox.markRead(notificationId)
            state.openNotification(notificationId)
        }
        onNotificationHandled()
    }

    val strings = enpoStrings(locale.languageTag)
    val layoutDirection = if (locale.direction == ContentDirection.RightToLeft) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        EnpoTheme(appearance.resolved) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                if (bootstrapState == EnpoBootstrapState.Initializing || !visibleLaunch.isComplete) {
                    EnpoSplash(strings)
                } else {
                    EnpoShell(
                        destination = backStack.last(),
                        strings = strings,
                        isDark = appearance.resolved == ResolvedAppearance.Dark,
                        runtimeVersionName = runtimeVersionName,
                        selectedLanguage = locale.languageTag,
                        selectedAppearance = appearance.preference,
                        bootstrapState = bootstrapState,
                        pairingState = pairingState,
                        notificationsEnabled = notificationsEnabled,
                        notificationSound = notificationSound,
                        notifications = notifications,
                        selectedNotificationId = selectedNotificationId,
                        profileState = profileState,
                        onOpen = state::open,
                        onOpenPaired = state::openPairedDestination,
                        onOpenNotifications = {
                            notificationPermissionRequester.requestIfAppropriate()
                            state.openPairedDestination(EnpoDestination.Notifications)
                        },
                        onSelectNotification = state::openNotification,
                        onCloseNotificationDetail = state::closeNotificationDetail,
                        onMarkNotificationRead = { id -> scope.launch { notificationInbox.markRead(id) } },
                        onMarkAllNotificationsRead = { scope.launch { notificationInbox.markAllRead() } },
                        onOpenNotificationAction = notificationActionHandler::open,
                        onBack = state::navigateBack,
                        onLanguage = state::selectLanguage,
                        onAppearance = state::selectAppearance,
                        onNotificationsEnabled = state::setNotificationsEnabled,
                        onNotificationSound = state::selectNotificationSound,
                        onRetryProfile = { scope.launch { profileController.refresh() } },
                        onStartPairing = {
                            scope.launch {
                                state.startPairing(strings.scannerPrompt)
                                if (state.pairingState.value == EnpoPairingState.Paired) {
                                    onPairingCompleted()
                                }
                            }
                        },
                        onEnterPairedShell = state::enterPairedShell,
                    )
                }
            }
        }
    }

    EnpoSystemBackHandler(
        enabled = selectedNotificationId != null || backStack.size > 1,
        onBack = {
            if (selectedNotificationId != null) state.closeNotificationDetail()
            else state.navigateBack()
        },
    )
}

@Composable
private fun EnpoShell(
    destination: EnpoDestination,
    strings: EnpoStrings,
    isDark: Boolean,
    runtimeVersionName: String,
    selectedLanguage: String,
    selectedAppearance: AppearancePreference,
    bootstrapState: EnpoBootstrapState,
    pairingState: EnpoPairingState,
    notificationsEnabled: Boolean,
    notificationSound: EnpoNotificationSound,
    notifications: List<SemanticNotification>,
    selectedNotificationId: String?,
    profileState: ProfileLoadState,
    onOpen: (EnpoDestination) -> Unit,
    onOpenPaired: (EnpoDestination) -> Unit,
    onOpenNotifications: () -> Unit,
    onSelectNotification: (String) -> Unit,
    onCloseNotificationDetail: () -> Unit,
    onMarkNotificationRead: (String) -> Unit,
    onMarkAllNotificationsRead: () -> Unit,
    onOpenNotificationAction: (SemanticNotificationDestination) -> Unit,
    onBack: () -> Boolean,
    onLanguage: (String) -> Unit,
    onAppearance: (AppearancePreference) -> Unit,
    onNotificationsEnabled: (Boolean) -> Unit,
    onNotificationSound: (EnpoNotificationSound) -> Unit,
    onRetryProfile: () -> Unit,
    onStartPairing: () -> Unit,
    onEnterPairedShell: () -> Unit,
) {
    val unreadCount = notifications.count { !it.isRead }
    val openSettings = { onOpenPaired(EnpoDestination.Settings) }
    val openProfile = { onOpenPaired(EnpoDestination.Profile) }
    when (destination) {
        EnpoDestination.Pairing -> PairingScreen(
            strings,
            isDark,
            pairingState,
            onStartPairing,
            onLanguage = { onOpen(EnpoDestination.Language) },
            onTheme = { onOpen(EnpoDestination.Theme) },
        )
        EnpoDestination.PairingSuccess -> PairingSuccessScreen(strings, isDark, onEnterPairedShell)
        EnpoDestination.Home -> HomeScreen(
            strings, isDark, bootstrapState, unreadCount, openSettings, onOpenNotifications, openProfile,
        )
        EnpoDestination.Notifications -> EnpoNotificationsScreen(
            strings = strings,
            isArabic = selectedLanguage == EnpoAppState.ArabicLanguageTag,
            notifications = notifications,
            selectedId = selectedNotificationId,
            onSelect = onSelectNotification,
            onCloseDetail = onCloseNotificationDetail,
            onMarkRead = onMarkNotificationRead,
            onMarkAllRead = onMarkAllNotificationsRead,
            onOpenAction = onOpenNotificationAction,
            onBack = { onBack() },
            onSettings = openSettings,
            onNotifications = onOpenNotifications,
            onProfile = openProfile,
        )
        EnpoDestination.NotificationSettings -> NotificationSettingsScreen(
            strings = strings,
            enabled = notificationsEnabled,
            selectedSound = notificationSound,
            onEnabled = onNotificationsEnabled,
            onSound = onNotificationSound,
            onBack = onBack,
        )
        EnpoDestination.Profile -> ProfileScreen(
            strings,
            profileState,
            unreadCount,
            openSettings,
            onOpenNotifications,
            openProfile,
            onRetryProfile,
        )
        EnpoDestination.Settings -> SettingsScreen(
            strings = strings,
            unreadCount = unreadCount,
            onOpenNotifications = onOpenNotifications,
            onOpen = onOpen,
            onSettings = openSettings,
            onProfile = openProfile,
        )
        EnpoDestination.Language -> SelectionScreen(
            strings.language,
            listOf(strings.arabic to EnpoAppState.ArabicLanguageTag, strings.english to EnpoAppState.EnglishLanguageTag),
            selectedLanguage,
            onLanguage,
            strings,
            onBack,
        )
        EnpoDestination.Theme -> SelectionScreen(
            strings.appearance,
            listOf(
                strings.system to AppearancePreference.System,
                strings.light to AppearancePreference.Light,
                strings.dark to AppearancePreference.Dark,
            ),
            selectedAppearance,
            onAppearance,
            strings,
            onBack,
        )
        EnpoDestination.DeviceStatus -> DeviceStatusScreen(strings, bootstrapState, onBack)
        EnpoDestination.PairingInfo -> PairingInfoScreen(strings, bootstrapState, onBack)
        EnpoDestination.About -> AboutScreen(strings, isDark, runtimeVersionName, onBack)
    }
}

@Composable
private fun PairingScreen(
    strings: EnpoStrings,
    isDark: Boolean,
    state: EnpoPairingState,
    onStartPairing: () -> Unit,
    onLanguage: () -> Unit,
    onTheme: () -> Unit,
) {
    val busy = state == EnpoPairingState.Scanning || state == EnpoPairingState.Validating ||
        state == EnpoPairingState.Claiming || state == EnpoPairingState.PersistingCredential
    val canRetry = state == EnpoPairingState.Unpaired || state is EnpoPairingState.RecoverableError
    StandaloneColumn {
        EnpoBrandHeader(isDark)
        Spacer(Modifier.height(26.dp))
        Text(strings.pairingTitle, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(strings.pairingBody, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .7f))
        Spacer(Modifier.height(20.dp))
        ProductCard(strings.deviceState, strings.pairingStateText(state))
        if (busy) {
            Spacer(Modifier.height(22.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        if (canRetry) {
            Spacer(Modifier.height(22.dp))
            Button(onClick = onStartPairing, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(if (state is EnpoPairingState.RecoverableError) strings.retry else strings.scanQr)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onLanguage, modifier = Modifier.weight(1f)) { Text(strings.language) }
            OutlinedButton(onClick = onTheme, modifier = Modifier.weight(1f)) { Text(strings.appearance) }
        }
    }
}

@Composable
private fun PairingSuccessScreen(strings: EnpoStrings, isDark: Boolean, onEnter: () -> Unit) {
    StandaloneColumn(horizontalAlignment = Alignment.CenterHorizontally) {
        EnpoBrandHeader(isDark)
        Spacer(Modifier.height(34.dp))
        Text(strings.pairingSuccessTitle, textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(strings.pairingSuccessBody, textAlign = TextAlign.Center)
        Spacer(Modifier.height(26.dp))
        Button(onClick = onEnter, modifier = Modifier.fillMaxWidth()) { Text(strings.continueToApp) }
    }
}

@Composable
private fun HomeScreen(
    strings: EnpoStrings,
    isDark: Boolean,
    bootstrapState: EnpoBootstrapState,
    unreadCount: Int,
    onSettings: () -> Unit,
    onNotifications: () -> Unit,
    onProfile: () -> Unit,
) {
    EnpoPairedScreen(strings, null, unreadCount, onSettings, onNotifications, onProfile) {
        EnpoBrandHeader(isDark)
        Spacer(Modifier.height(26.dp))
        Text(strings.welcome, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(strings.linkedAndSecure, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(22.dp))
        ProductCard(strings.deviceState, strings.deviceStateText(bootstrapState), emphasized = true)
        Spacer(Modifier.height(12.dp))
        ProductCard(strings.authenticationRequests, strings.noAuthenticationRequests)
        Spacer(Modifier.height(12.dp))
        ProductCard(strings.communications, strings.communicationsBody)
        Spacer(Modifier.height(12.dp))
        ProductCard(strings.security, strings.securityBody)
    }
}

@Composable
private fun ProfileScreen(
    strings: EnpoStrings,
    state: ProfileLoadState,
    unreadCount: Int,
    onSettings: () -> Unit,
    onNotifications: () -> Unit,
    onProfile: () -> Unit,
    onRetry: () -> Unit,
) {
    EnpoPairedScreen(strings, EnpoPairedTab.Profile, unreadCount, onSettings, onNotifications, onProfile) {
        Text(strings.profile, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(28.dp))
        when (state) {
            ProfileLoadState.NotLoaded,
            ProfileLoadState.Loading,
            -> Box(
                Modifier.fillMaxWidth().padding(vertical = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            is ProfileLoadState.Ready -> {
                ProfileAvatar(state.snapshot.displayName)
                Spacer(Modifier.height(16.dp))
                Text(
                    state.snapshot.displayName,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                state.snapshot.jobTitle?.let {
                    Spacer(Modifier.height(22.dp))
                    ProductCard(strings.jobTitle, it)
                }
                state.snapshot.organizationUnit?.let {
                    Spacer(Modifier.height(12.dp))
                    ProductCard(strings.organizationUnit, it)
                }
                Spacer(Modifier.height(12.dp))
                ProductCard(strings.deviceState, strings.pairedAndSecure, emphasized = true)
            }

            ProfileLoadState.NotAvailableYet ->
                ProductCard(strings.profileUnavailable, strings.profileUnavailableBody)

            ProfileLoadState.AuthenticationRequired -> {
                ProductCard(strings.profileLoadError, strings.profileAuthenticationRequired)
                Spacer(Modifier.height(18.dp))
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(strings.retry) }
            }

            ProfileLoadState.Error -> {
                ProductCard(strings.profileLoadError, strings.profileLoadErrorBody)
                Spacer(Modifier.height(18.dp))
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(strings.retry) }
            }
        }
    }
}

@Composable
private fun ColumnScope.ProfileAvatar(displayName: String) {
    val initials = displayName
        .split(" ")
        .filter(String::isNotBlank)
        .take(2)
        .mapNotNull { part -> part.firstOrNull()?.uppercaseChar() }
        .joinToString("")
        .ifBlank { "EN" }

    Surface(
        Modifier.size(96.dp).align(Alignment.CenterHorizontally),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = .12f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                initials,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    strings: EnpoStrings,
    unreadCount: Int,
    onOpenNotifications: () -> Unit,
    onOpen: (EnpoDestination) -> Unit,
    onSettings: () -> Unit,
    onProfile: () -> Unit,
) {
    EnpoPairedScreen(
        strings, EnpoPairedTab.Settings, unreadCount, onSettings, onOpenNotifications, onProfile,
    ) {
        Text(strings.settings, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(22.dp))
        SettingsSection(strings.appearanceAndLanguage) {
            SettingsRow(strings.language) { onOpen(EnpoDestination.Language) }
            SettingsRow(strings.appearance) { onOpen(EnpoDestination.Theme) }
        }
        Spacer(Modifier.height(18.dp))
        SettingsSection(strings.deviceAndPairing) {
            SettingsRow(strings.deviceStatus) { onOpen(EnpoDestination.DeviceStatus) }
            SettingsRow(strings.pairingInformation) { onOpen(EnpoDestination.PairingInfo) }
        }
        Spacer(Modifier.height(18.dp))
        SettingsSection(strings.notifications) {
            SettingsRow(strings.notificationSettings) { onOpen(EnpoDestination.NotificationSettings) }
        }
        Spacer(Modifier.height(18.dp))
        SettingsSection(strings.about) { SettingsRow(strings.about) { onOpen(EnpoDestination.About) } }
    }
}

@Composable
private fun NotificationSettingsScreen(
    strings: EnpoStrings,
    enabled: Boolean,
    selectedSound: EnpoNotificationSound,
    onEnabled: (Boolean) -> Unit,
    onSound: (EnpoNotificationSound) -> Unit,
    onBack: () -> Boolean,
) {
    StandaloneColumn {
        ScreenHeader(strings.notificationSettings, strings.back, onBack)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(strings.enableNotifications, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(strings.enableNotificationsBody, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f))
                }
                Switch(checked = enabled, onCheckedChange = onEnabled)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(strings.enpoSounds, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
            Column {
                EnpoNotificationSound.entries.forEach { sound ->
                    Surface(onClick = { onSound(sound) }, color = MaterialTheme.colorScheme.surface) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(sound.storageKey.replaceFirstChar(Char::uppercase), modifier = Modifier.weight(1f))
                            RadioButton(selected = sound == selectedSound, onClick = { onSound(sound) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceStatusScreen(strings: EnpoStrings, state: EnpoBootstrapState, onBack: () -> Boolean) {
    StandaloneColumn {
        ScreenHeader(strings.deviceStatus, strings.back, onBack)
        ProductCard(strings.deviceState, strings.deviceStateText(state), emphasized = true)
        Spacer(Modifier.height(12.dp))
        ProductCard(
            strings.credentialStorage,
            if (state == EnpoBootstrapState.DeviceCredentialAvailable) strings.encryptedAndAvailable else strings.credentialUnreadable,
        )
    }
}

@Composable
private fun PairingInfoScreen(strings: EnpoStrings, state: EnpoBootstrapState, onBack: () -> Boolean) {
    StandaloneColumn {
        ScreenHeader(strings.pairingInformation, strings.back, onBack)
        ProductCard(
            strings.pairingMode,
            if (state == EnpoBootstrapState.DeviceCredentialAvailable) strings.productionPublicService else strings.unpaired,
        )
        Spacer(Modifier.height(12.dp))
        ProductCard(strings.deviceState, strings.deviceStateText(state))
    }
}

@Composable
private fun <Value> SelectionScreen(
    title: String,
    options: List<Pair<String, Value>>,
    selected: Value,
    onSelect: (Value) -> Unit,
    strings: EnpoStrings,
    onBack: () -> Boolean,
) {
    StandaloneColumn {
        ScreenHeader(title, strings.back, onBack)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
            Column {
                options.forEach { (label, value) ->
                    Surface(onClick = { onSelect(value) }, color = MaterialTheme.colorScheme.surface) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, modifier = Modifier.weight(1f))
                            RadioButton(selected = value == selected, onClick = { onSelect(value) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutScreen(strings: EnpoStrings, isDark: Boolean, versionName: String, onBack: () -> Boolean) {
    StandaloneColumn(horizontalAlignment = Alignment.CenterHorizontally) {
        ScreenHeader(strings.about, strings.back, onBack)
        EnpoBrandHeader(isDark)
        Spacer(Modifier.height(24.dp))
        Text(strings.foundationTitle, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        ProductCard(strings.version, versionName.ifBlank { "-" })
        Spacer(Modifier.height(12.dp))
        Text(strings.productName, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .55f))
    }
}

@Composable
private fun StandaloneColumn(
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

@Composable
private fun ProductCard(title: String, body: String, emphasized: Boolean = false) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (emphasized) {
                MaterialTheme.colorScheme.primary.copy(alpha = .09f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(body, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = .6f),
    )
    Spacer(Modifier.height(8.dp))
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
        Column(content = content)
    }
}

@Composable
private fun SettingsRow(title: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ScreenHeader(title: String, back: String, onBack: () -> Boolean) {
    Row(Modifier.fillMaxWidth().padding(bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onBack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = back)
        }
        Text(
            title,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.size(48.dp))
    }
}
