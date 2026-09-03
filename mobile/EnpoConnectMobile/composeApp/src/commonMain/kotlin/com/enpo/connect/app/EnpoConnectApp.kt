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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.botglobal.mobile.platform.preferences.InMemoryPreferenceStore
import com.botglobal.mobile.platform.preferences.PreferenceStore
import com.enpo.connect.app.network.EnpoNetworkConfiguration
import com.enpo.connect.app.pairing.EnpoPairingCoordinator
import com.enpo.connect.app.pairing.EnpoPairingState
import com.enpo.connect.app.state.EnpoAppState
import com.enpo.connect.app.state.EnpoBootstrapState
import com.enpo.connect.app.state.EmptyEnpoDeviceInfrastructure
import com.enpo.connect.app.state.EnpoDeviceInfrastructure
import com.enpo.connect.app.state.EnpoDestination
import com.enpo.connect.app.ui.EnpoStrings
import com.enpo.connect.app.ui.EnpoSystemBackHandler
import com.enpo.connect.app.ui.EnpoTheme
import com.enpo.connect.app.ui.enpoStrings
import kotlinx.coroutines.launch

@Composable
fun EnpoConnectApp(
    runtimeVersionName: String,
    preferences: PreferenceStore = InMemoryPreferenceStore(),
    deviceInfrastructure: EnpoDeviceInfrastructure = EmptyEnpoDeviceInfrastructure,
    networkConfiguration: EnpoNetworkConfiguration? = null,
    pairingCoordinator: EnpoPairingCoordinator = EnpoPairingCoordinator(),
    onResolvedAppearanceChanged: (ResolvedAppearance) -> Unit = {},
) {
    val state = remember(preferences, deviceInfrastructure, networkConfiguration, pairingCoordinator) {
        EnpoAppState(
            preferences = preferences,
            deviceInfrastructure = deviceInfrastructure,
            networkConfiguration = networkConfiguration,
            pairingCoordinator = pairingCoordinator,
        )
    }
    val locale by state.locale.state.collectAsState()
    val appearance by state.appearance.state.collectAsState()
    val backStack by state.navigation.backStack.collectAsState()
    val bootstrapState by state.bootstrapState.collectAsState()
    val pairingState by state.pairingState.collectAsState()
    val scope = rememberCoroutineScope()
    val systemIsDark = isSystemInDarkTheme()

    LaunchedEffect(state) { state.bootstrap() }
    LaunchedEffect(systemIsDark) { state.appearance.updateSystemAppearance(systemIsDark) }
    LaunchedEffect(appearance.resolved) { onResolvedAppearanceChanged(appearance.resolved) }

    val strings = enpoStrings(locale.languageTag)
    val layoutDirection = if (locale.direction == ContentDirection.RightToLeft) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        EnpoTheme(appearance.resolved) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                if (bootstrapState == EnpoBootstrapState.Initializing) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    EnpoShell(
                        destination = backStack.last(),
                        strings = strings,
                        runtimeVersionName = runtimeVersionName,
                        selectedLanguage = locale.languageTag,
                        selectedAppearance = appearance.preference,
                        bootstrapState = bootstrapState,
                        pairingState = pairingState,
                        onOpen = state::open,
                        onBack = state::navigateBack,
                        onLanguage = state::selectLanguage,
                        onAppearance = state::selectAppearance,
                        onStartPairing = {
                            scope.launch { state.startPairing(strings.scannerPrompt) }
                        },
                        onEnterPairedShell = state::enterPairedShell,
                    )
                }
            }
        }
    }

    EnpoSystemBackHandler(
        enabled = backStack.size > 1,
        onBack = { state.navigateBack() },
    )
}

@Composable
private fun EnpoShell(
    destination: EnpoDestination,
    strings: EnpoStrings,
    runtimeVersionName: String,
    selectedLanguage: String,
    selectedAppearance: AppearancePreference,
    bootstrapState: EnpoBootstrapState,
    pairingState: EnpoPairingState,
    onOpen: (EnpoDestination) -> Unit,
    onBack: () -> Boolean,
    onLanguage: (String) -> Unit,
    onAppearance: (AppearancePreference) -> Unit,
    onStartPairing: () -> Unit,
    onEnterPairedShell: () -> Unit,
) {
    when (destination) {
        EnpoDestination.Pairing -> PairingScreen(
            strings = strings,
            state = pairingState,
            onStartPairing = onStartPairing,
            onSettings = { onOpen(EnpoDestination.Settings) },
        )
        EnpoDestination.PairingSuccess -> PairingSuccessScreen(strings, onEnterPairedShell)
        EnpoDestination.Home -> HomeScreen(strings, bootstrapState, onOpen)
        EnpoDestination.Settings -> SettingsScreen(strings, onOpen, onBack)
        EnpoDestination.Language -> SelectionScreen(
            title = strings.language,
            options = listOf(
                strings.arabic to EnpoAppState.ArabicLanguageTag,
                strings.english to EnpoAppState.EnglishLanguageTag,
            ),
            selected = selectedLanguage,
            onSelect = onLanguage,
            strings = strings,
            onBack = onBack,
        )
        EnpoDestination.Theme -> SelectionScreen(
            title = strings.appearance,
            options = listOf(
                strings.system to AppearancePreference.System,
                strings.light to AppearancePreference.Light,
                strings.dark to AppearancePreference.Dark,
            ),
            selected = selectedAppearance,
            onSelect = onAppearance,
            strings = strings,
            onBack = onBack,
        )
        EnpoDestination.About -> AboutScreen(strings, runtimeVersionName, onBack)
    }
}

@Composable
private fun PairingScreen(
    strings: EnpoStrings,
    state: EnpoPairingState,
    onStartPairing: () -> Unit,
    onSettings: () -> Unit,
) {
    val busy = state == EnpoPairingState.Scanning ||
        state == EnpoPairingState.Validating ||
        state == EnpoPairingState.Claiming ||
        state == EnpoPairingState.PersistingCredential
    val canRetry = state == EnpoPairingState.Unpaired || state is EnpoPairingState.RecoverableError

    ShellColumn {
        BrandHeader(strings)
        Spacer(Modifier.height(30.dp))
        Text(
            strings.pairingTitle,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        Text(strings.pairingBody, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        StatusCard(strings.deviceState, strings.pairingStateText(state))
        if (busy) {
            Spacer(Modifier.height(24.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        if (canRetry) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onStartPairing, modifier = Modifier.fillMaxWidth()) {
                Text(if (state is EnpoPairingState.RecoverableError) strings.retry else strings.scanQr)
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
            Text(strings.settings)
        }
    }
}

@Composable
private fun PairingSuccessScreen(
    strings: EnpoStrings,
    onEnterPairedShell: () -> Unit,
) {
    ShellColumn {
        BrandHeader(strings)
        Spacer(Modifier.height(36.dp))
        Text(
            strings.pairingSuccessTitle,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            strings.pairingSuccessBody,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onEnterPairedShell, modifier = Modifier.fillMaxWidth()) {
            Text(strings.continueToApp)
        }
    }
}

@Composable
private fun HomeScreen(
    strings: EnpoStrings,
    bootstrapState: EnpoBootstrapState,
    onOpen: (EnpoDestination) -> Unit,
) {
    ShellColumn {
        BrandHeader(strings)
        Spacer(Modifier.height(30.dp))
        Text(
            strings.foundationEyebrow,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            strings.foundationTitle,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        Text(strings.foundationBody, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        StatusCard(strings.platformFoundation, strings.platformFoundationBody)
        Spacer(Modifier.height(12.dp))
        StatusCard(strings.deviceState, strings.deviceStateText(bootstrapState))
        Spacer(Modifier.height(12.dp))
        StatusCard(strings.deferredCapabilities, strings.deferredCapabilitiesBody)
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onOpen(EnpoDestination.Settings) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(strings.settings)
        }
        OutlinedButton(
            onClick = { onOpen(EnpoDestination.About) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(strings.about)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            strings.sliceNotice,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = .62f),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SettingsScreen(
    strings: EnpoStrings,
    onOpen: (EnpoDestination) -> Unit,
    onBack: () -> Boolean,
) {
    ShellColumn {
        ScreenHeader(strings.settings, strings.back, onBack)
        SettingsRow(strings.language) { onOpen(EnpoDestination.Language) }
        SettingsRow(strings.appearance) { onOpen(EnpoDestination.Theme) }
        SettingsRow(strings.about) { onOpen(EnpoDestination.About) }
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
    ShellColumn {
        ScreenHeader(title, strings.back, onBack)
        options.forEach { (label, value) ->
            val isSelected = value == selected
            OutlinedButton(
                onClick = { onSelect(value) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isSelected) "✓  $label" else label)
            }
        }
    }
}

@Composable
private fun AboutScreen(
    strings: EnpoStrings,
    runtimeVersionName: String,
    onBack: () -> Boolean,
) {
    ShellColumn {
        ScreenHeader(strings.about, strings.back, onBack)
        BrandHeader(strings)
        Spacer(Modifier.height(24.dp))
        StatusCard(
            strings.version,
            runtimeVersionName.ifBlank { "—" },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            strings.foundationTitle,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun ShellColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.Top,
        content = content,
    )
}

@Composable
private fun BrandHeader(strings: EnpoStrings) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
        ) {
            Box(Modifier.padding(horizontal = 17.dp, vertical = 12.dp)) {
                Text(
                    "E",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                )
            }
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(strings.productName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Text(
                strings.organizationName,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = .62f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun StatusCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(body, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
        }
    }
}

@Composable
private fun SettingsRow(title: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Text(title, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
        Text("›")
    }
}

@Composable
private fun ScreenHeader(title: String, back: String, onBack: () -> Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = { onBack() }) { Text(back) }
        Text(
            title,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}
