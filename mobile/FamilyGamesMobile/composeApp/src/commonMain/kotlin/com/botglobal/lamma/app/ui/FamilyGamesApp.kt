package com.botglobal.lamma.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.window.DialogProperties
import com.botglobal.lamma.app.state.AccountDeletionConfirmation
import com.botglobal.lamma.app.data.AccountDeletionAcceptance
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Instant
import com.botglobal.lamma.app.data.FamilyGamesApi
import com.botglobal.lamma.app.data.FamilyGamesEnvironment
import com.botglobal.lamma.app.data.AutobusSnapshot
import com.botglobal.lamma.app.data.GameSessionSnapshot
import com.botglobal.lamma.app.data.PlayerSnapshot
import com.botglobal.lamma.app.data.createPlatformHttpClient
import com.botglobal.lamma.app.realtime.createGameRealtimeClient
import com.botglobal.lamma.app.state.AppLanguage
import com.botglobal.lamma.app.state.AppScreen
import com.botglobal.lamma.app.state.ApplicationLanguagePreferences
import com.botglobal.lamma.app.state.FamilyGamesCoordinator
import com.botglobal.lamma.app.state.FamilyGamesUiState
import com.botglobal.lamma.app.state.OpponentConnectionState
import com.botglobal.lamma.app.state.RecentGameSessionPreferences
import com.botglobal.lamma.app.state.UnavailableApplicationLanguagePreferences
import com.botglobal.lamma.app.state.UnavailableRecentGameSessionPreferences
import com.botglobal.mobile.platform.device.PermissionController
import com.botglobal.mobile.platform.device.SemanticHaptics
import com.botglobal.mobile.platform.device.UnavailablePermissionController
import com.botglobal.mobile.platform.identity.SessionVault
import com.botglobal.mobile.platform.identity.FederatedCredentialProvider
import com.botglobal.mobile.platform.identity.UnavailableFederatedCredentialProvider
import com.botglobal.mobile.platform.invitations.GameInvitation
import com.botglobal.mobile.platform.invitations.InvitationLinkCodec
import com.botglobal.mobile.platform.invitations.PlatformShareCapability
import com.botglobal.mobile.platform.invitations.QrScannerCapability
import com.botglobal.mobile.platform.invitations.UnavailablePlatformShare
import com.botglobal.mobile.platform.invitations.UnavailableQrScanner
import com.botglobal.mobile.platform.realtime.RealtimeConnectionState
import com.botglobal.mobile.platform.realtime.NetworkAvailability
import com.botglobal.mobile.platform.realtime.UnavailableNetworkAvailability
import com.botglobal.mobile.platform.reviews.ReviewCoordinator
import com.botglobal.mobile.platform.voice.VoiceMediaPeerFactory
import com.botglobal.mobile.platform.voice.VoiceRoomState
import com.botglobal.mobile.platform.voice.VoiceConsentState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@Composable
fun FamilyGamesApp(
    apiBaseUrl: String,
    sessionVault: SessionVault,
    haptics: SemanticHaptics,
    appVersion: String = "0.1.0",
    platform: String = "android",
    openExternalUrl: (String) -> Unit = {},
    foregroundEvents: Flow<Unit> = emptyFlow(),
    backgroundEvents: Flow<Unit> = emptyFlow(),
    invitationLinks: Flow<String> = emptyFlow(),
    invitationLinkBase: String = "familygames://invite",
    platformShare: PlatformShareCapability = UnavailablePlatformShare,
    qrScanner: QrScannerCapability = UnavailableQrScanner,
    permissions: PermissionController = UnavailablePermissionController,
    networkAvailability: NetworkAvailability = UnavailableNetworkAvailability,
    languagePreferences: ApplicationLanguagePreferences = UnavailableApplicationLanguagePreferences,
    recentGameSessionPreferences: RecentGameSessionPreferences = UnavailableRecentGameSessionPreferences,
    federatedCredentials: FederatedCredentialProvider = UnavailableFederatedCredentialProvider,
    reviews: ReviewCoordinator? = null,
    voiceMediaFactory: VoiceMediaPeerFactory? = null,
    diagnosticsEnabled: Boolean = false,
    invitationQr: @Composable (String, String, Modifier) -> Unit = { _, description, modifier ->
        Box(
            modifier
                .background(Color.White)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) { Text("QR", color = Color.Black, fontSize = 36.sp, fontWeight = FontWeight.Black) }
    },
) {
    val scope = rememberCoroutineScope()
    val environment = remember(apiBaseUrl) { FamilyGamesEnvironment.from(apiBaseUrl) }
    val coordinator = remember(
        environment,
        sessionVault,
        haptics,
        appVersion,
        platform,
        invitationLinkBase,
        platformShare,
        qrScanner,
        permissions,
        networkAvailability,
        languagePreferences,
        recentGameSessionPreferences,
        federatedCredentials,
        reviews,
        voiceMediaFactory,
        diagnosticsEnabled,
    ) {
        val gateway = FamilyGamesApi(createPlatformHttpClient(), environment, sessionVault)
        FamilyGamesCoordinator(
            gateway,
            createGameRealtimeClient(environment, diagnosticsEnabled),
            haptics,
            scope,
            appVersion,
            platform,
            InvitationLinkCodec(invitationLinkBase),
            platformShare,
            qrScanner,
            permissions,
            networkAvailability,
            languagePreferences,
            recentGameSessionPreferences,
            federatedCredentials,
            reviews,
            voiceMediaFactory,
        )
    }
    val state by coordinator.state.collectAsState()
    val text = strings(state.language)
    val direction = if (state.language == AppLanguage.Arabic) LayoutDirection.Rtl else LayoutDirection.Ltr

    LaunchedEffect(coordinator) { coordinator.startup() }
    LaunchedEffect(coordinator, foregroundEvents) {
        foregroundEvents.collect { coordinator.resumeAfterForeground() }
    }
    LaunchedEffect(coordinator, backgroundEvents) {
        backgroundEvents.collect { coordinator.pauseForBackground() }
    }
    LaunchedEffect(coordinator, invitationLinks) {
        invitationLinks.collect(coordinator::handleInvitationLink)
    }
    DisposableEffect(coordinator) {
        onDispose(coordinator::dispose)
    }

    FamilyGamesTheme {
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            Surface(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(FamilyGamesColors.Night, Color(0xFF21183B), FamilyGamesColors.Night),
                            ),
                        ),
                ) {
                    when (state.screen) {
                        AppScreen.Startup -> StartupScreen(text)
                        AppScreen.Welcome -> WelcomeScreen(text, state, coordinator)
                        AppScreen.SignIn -> SignInScreen(text, coordinator)
                        AppScreen.Register -> RegisterScreen(text, coordinator)
                        AppScreen.ProfileCompletion -> ProfileCompletionScreen(text, state, coordinator)
                        AppScreen.Home -> HomeScreen(text, state, coordinator, openExternalUrl)
                        AppScreen.Ruleset -> RulesetScreen(text, coordinator)
                        AppScreen.AutobusSetup -> AutobusSetupScreen(text, state, coordinator)
                        AppScreen.CreateOrJoin -> CreateJoinScreen(text, coordinator)
                        AppScreen.Lobby -> LobbyScreen(text, state, coordinator)
                        AppScreen.Gameplay -> GameplayScreen(text, state, coordinator)
                        AppScreen.Result -> ResultScreen(text, state, coordinator)
                        AppScreen.RequiredUpdate -> RequiredUpdateScreen(text, state, openExternalUrl)
                    }

                    AnimatedVisibility(
                        visible = state.errorCode != null,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) {
                        ErrorBanner(text.error(state.errorCode))
                    }
                    if (state.busy && state.accountDeletionConfirmation == null) LoadingOverlay(text.loading)
                    if (state.accountDeletionConfirmation != null) {
                        AccountDeletionDialog(text, state, coordinator)
                    }
                    state.invitation?.let { invitation ->
                        InvitationSurface(
                            text = text,
                            invitation = invitation,
                            invitationQr = invitationQr,
                            onShare = { coordinator.shareInvitation(if (state.game?.gameType == "autobus") text.autobusTitle else text.xoTitle) },
                            onDismiss = coordinator::dismissInvitation,
                        )
                    }
                    if (state.cameraExplanationVisible) {
                        CameraExplanationDialog(
                            text,
                            { coordinator.confirmCameraAndScan(text.scanInvitationPrompt) },
                            coordinator::dismissCameraExplanation,
                        )
                    }
                    if (state.voiceExplanationVisible) {
                        VoiceExplanationDialog(
                            text,
                            coordinator::confirmMicrophoneAndJoinVoice,
                            coordinator::dismissVoiceExplanation,
                        )
                    }
                    if (state.voiceConsent.state == VoiceConsentState.IncomingRequest) {
                        VoiceRequestDialog(
                            text = text,
                            onAccept = coordinator::acceptVoiceRequest,
                            onDecline = coordinator::declineVoiceRequest,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StartupScreen(text: FamilyGamesStrings) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TemporaryLogo()
            Spacer(Modifier.height(FamilyGamesSpacing.Lg))
            Text(text.appName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(FamilyGamesSpacing.Lg))
            CircularProgressIndicator(color = FamilyGamesColors.Gold)
        }
    }
}

@Composable
private fun WelcomeScreen(
    text: FamilyGamesStrings,
    state: FamilyGamesUiState,
    coordinator: FamilyGamesCoordinator,
) {
    var displayName by remember { mutableStateOf("") }
    Page {
        state.accountDeletionAcceptance?.let { acceptance ->
            Text(if (acceptance == AccountDeletionAcceptance.Pending) text.deletionPending else text.deletionCompleted,
                modifier = Modifier.fillMaxWidth().padding(vertical = FamilyGamesSpacing.Md))
        }

        TopLanguage(text, coordinator)
        Spacer(Modifier.weight(1f))
        TemporaryLogo()
        Spacer(Modifier.height(FamilyGamesSpacing.Lg))
        Text(text.appName, fontSize = 36.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        Text(text.tagline, color = FamilyGamesColors.Muted, fontSize = 18.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(FamilyGamesSpacing.Xl))
        PrimaryButton(text.continueWithGoogle, !state.busy, coordinator::signInWithGoogle)
        Spacer(Modifier.height(FamilyGamesSpacing.Md))
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it.take(40) },
            label = { Text(text.displayName) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Spacer(Modifier.height(FamilyGamesSpacing.Sm))
        OutlinedButton(
            onClick = { coordinator.continueAsGuest(displayName) },
            enabled = displayName.isNotBlank() && !state.busy,
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Text(text.continueGuest, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(FamilyGamesSpacing.Sm))
        OutlinedButton(onClick = coordinator::showSignIn, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Text(text.signIn)
        }
        TextButton(onClick = coordinator::showRegister, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(text.createAccount, color = FamilyGamesColors.Gold)
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ProfileCompletionScreen(
    text: FamilyGamesStrings,
    state: FamilyGamesUiState,
    coordinator: FamilyGamesCoordinator,
) {
    FormPage(
        text.profileCompletionTitle,
        if (state.profileCompletionRequired) text.logout else text.back,
        if (state.profileCompletionRequired) coordinator::logout else coordinator::backHome,
    ) {
        Text(text.profileCompletionBody, color = FamilyGamesColors.Muted)
        Spacer(Modifier.height(FamilyGamesSpacing.Lg))
        OutlinedTextField(
            value = state.profileDraft,
            onValueChange = coordinator::updateProfileDraft,
            label = { Text(text.displayName) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Spacer(Modifier.height(FamilyGamesSpacing.Lg))
        PrimaryButton(
            text.saveProfile,
            state.profileDraft.isNotBlank() && !state.busy,
            coordinator::completeProfile,
        )
    }
}

@Composable
private fun SignInScreen(text: FamilyGamesStrings, coordinator: FamilyGamesCoordinator) {
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    FormPage(text.signIn, text.back, coordinator::backToWelcome) {
        OutlinedTextField(login, { login = it }, label = { Text(text.userNameOrEmail) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(FamilyGamesSpacing.Md))
        PasswordField(password, { password = it }, text.password)
        Spacer(Modifier.height(FamilyGamesSpacing.Lg))
        PrimaryButton(text.signIn, login.isNotBlank() && password.isNotBlank()) { coordinator.signIn(login, password) }
        TextButton(onClick = coordinator::showRegister, modifier = Modifier.fillMaxWidth()) { Text(text.createAccount) }
    }
}

@Composable
private fun RegisterScreen(text: FamilyGamesStrings, coordinator: FamilyGamesCoordinator) {
    var userName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    FormPage(text.createAccount, text.back, coordinator::backToWelcome) {
        OutlinedTextField(displayName, { displayName = it }, label = { Text(text.displayName) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(FamilyGamesSpacing.Sm))
        OutlinedTextField(userName, { userName = it }, label = { Text(text.userName) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(FamilyGamesSpacing.Sm))
        OutlinedTextField(
            email,
            { email = it },
            label = { Text(text.email) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(FamilyGamesSpacing.Sm))
        PasswordField(password, { password = it }, text.password)
        Spacer(Modifier.height(FamilyGamesSpacing.Lg))
        PrimaryButton(
            text.createAccount,
            userName.isNotBlank() && email.isNotBlank() && displayName.isNotBlank() && password.length >= 12,
        ) { coordinator.register(userName, email, displayName, password) }
    }
}

@Composable
private fun HomeScreen(
    text: FamilyGamesStrings,
    state: FamilyGamesUiState,
    coordinator: FamilyGamesCoordinator,
    openExternalUrl: (String) -> Unit,
) {
    Page {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(text.welcome, color = FamilyGamesColors.Muted)
                Text(state.mobileSession?.identity?.displayName.orEmpty(), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = coordinator::toggleLanguage) { Text(text.language) }
        }
        Spacer(Modifier.height(FamilyGamesSpacing.Xl))
        Text(text.catalogTitle, fontSize = 30.sp, fontWeight = FontWeight.Black)
        Text(text.catalogSubtitle, color = FamilyGamesColors.Muted)
        AnimatedVisibility(state.optionalUpdateVisible) {
            UpdateCard(
                title = text.updateAvailable,
                message = state.updateMessage ?: text.optionalUpdateMessage,
                action = text.updateNow,
                dismiss = text.updateLater,
                actionEnabled = state.storeDestination != null,
                onAction = { state.storeDestination?.let(openExternalUrl) },
                onDismiss = coordinator::dismissOptionalUpdate,
            )
        }
        Spacer(Modifier.height(FamilyGamesSpacing.Lg))
        Card(
            modifier = Modifier.fillMaxWidth().clickable(onClick = coordinator::showRuleset),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.linearGradient(listOf(FamilyGamesColors.Purple, FamilyGamesColors.Coral)))
                    .padding(FamilyGamesSpacing.Lg),
            ) {
                Column {
                    Text("X  O", fontSize = 46.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(FamilyGamesSpacing.Md))
                    Text(text.xoTitle, fontSize = 28.sp, fontWeight = FontWeight.Black)
                    Text(text.xoSubtitle, color = FamilyGamesColors.Cream.copy(alpha = .82f))
                    Spacer(Modifier.height(FamilyGamesSpacing.Lg))
                    Button(
                        onClick = coordinator::showRuleset,
                        colors = ButtonDefaults.buttonColors(containerColor = FamilyGamesColors.Gold, contentColor = FamilyGamesColors.Night),
                    ) { Text(text.play, fontWeight = FontWeight.Bold) }
                }
            }
        }
        Spacer(Modifier.height(FamilyGamesSpacing.Md))
        Card(
            modifier = Modifier.fillMaxWidth().clickable(onClick = coordinator::showAutobusSetup),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = FamilyGamesColors.NightSoft),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(FamilyGamesSpacing.Lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(74.dp).clip(RoundedCornerShape(18.dp))
                        .background(FamilyGamesColors.Gold.copy(alpha = .18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("أ", fontSize = 38.sp, fontWeight = FontWeight.Black, color = FamilyGamesColors.Gold)
                }
                Spacer(Modifier.width(FamilyGamesSpacing.Md))
                Column(Modifier.weight(1f)) {
                    Text(text.autobusTitle, fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text(text.autobusSubtitle, color = FamilyGamesColors.Muted)
                }
                Button(
                    onClick = coordinator::showAutobusSetup,
                    colors = ButtonDefaults.buttonColors(containerColor = FamilyGamesColors.Gold, contentColor = FamilyGamesColors.Night),
                ) { Text(text.play, fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(Modifier.weight(1f))
        if (state.mobileSession?.identity?.kind == com.botglobal.mobile.platform.identity.IdentityKind.Registered) {
            TextButton(onClick = coordinator::editProfile, enabled = !state.busy,
                modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(text.editProfile, color = FamilyGamesColors.Gold)
            }
            TextButton(onClick = coordinator::beginAccountDeletion, enabled = !state.busy,
                modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(text.deleteAccount, color = MaterialTheme.colorScheme.error)
            }
        }
        TextButton(onClick = coordinator::logout, enabled = !state.busy,
            modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(text.logout, color = FamilyGamesColors.Muted)
        }
    }
}

@Composable
private fun AutobusSetupScreen(
    text: FamilyGamesStrings,
    state: FamilyGamesUiState,
    coordinator: FamilyGamesCoordinator,
) {
    var rounds by remember { mutableStateOf(5) }
    var seconds by remember { mutableStateOf(60) }
    var difficulty by remember { mutableStateOf("medium") }
    var selectedCategories by remember {
        mutableStateOf(AutobusSetupCategories.filter { it.selectedByDefault }.map { it.key }.toSet())
    }
    FormPage(text.autobusSetupTitle, text.back, coordinator::backHome) {
        OptionRow(text.autobusRounds, listOf(5 to "5", 10 to "10"), rounds) { rounds = it }
        Spacer(Modifier.height(FamilyGamesSpacing.Md))
        OptionRow(text.autobusTimer, listOf(60 to "60s", 90 to "90s"), seconds) { seconds = it }
        Spacer(Modifier.height(FamilyGamesSpacing.Md))
        Text(text.autobusDifficulty, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(FamilyGamesSpacing.Sm))
        Row(horizontalArrangement = Arrangement.spacedBy(FamilyGamesSpacing.Sm)) {
            listOf(
                "easy" to text.autobusDifficultyEasy,
                "medium" to text.autobusDifficultyMedium,
                "hard" to text.autobusDifficultyHard,
            ).forEach { (value, label) ->
                OutlinedButton(
                    onClick = { difficulty = value },
                    modifier = Modifier.weight(1f).height(48.dp).semantics { selected = difficulty == value },
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (difficulty == value) FamilyGamesColors.Gold.copy(alpha = .22f) else Color.Transparent,
                    ),
                ) { Text(label) }
            }
        }
        Spacer(Modifier.height(FamilyGamesSpacing.Lg))
        Text(text.autobusCategories, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(FamilyGamesSpacing.Sm))
        AutobusSetupCategories.chunked(2).forEach { categories ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FamilyGamesSpacing.Sm),
            ) {
                categories.forEach { category ->
                    val selected = category.key in selectedCategories
                    AutobusCategoryChoice(
                        label = category.label(state.language),
                        selected = selected,
                        modifier = Modifier.weight(1f),
                    ) { checked ->
                        selectedCategories = if (checked) {
                            selectedCategories + category.key
                        } else {
                            selectedCategories - category.key
                        }
                    }
                }
                if (categories.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton(text.autobusStartRoom, selectedCategories.isNotEmpty()) {
            coordinator.createAutobusGame(rounds, seconds, difficulty, selectedCategories.toList())
        }
    }
}

@Composable
private fun AutobusCategoryChoice(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onSelectedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = modifier
            .sizeIn(minHeight = 48.dp)
            .toggleable(
                value = selected,
                role = Role.Checkbox,
                onValueChange = onSelectedChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = null)
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
    }
}

@Composable
private fun OptionRow(
    title: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Text(title, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(FamilyGamesSpacing.Sm))
    Row(horizontalArrangement = Arrangement.spacedBy(FamilyGamesSpacing.Sm)) {
        options.forEach { (value, label) ->
            OutlinedButton(
                onClick = { onSelect(value) },
                modifier = Modifier.weight(1f).height(48.dp).semantics { this.selected = selected == value },
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (selected == value) FamilyGamesColors.Gold.copy(alpha = .22f) else Color.Transparent,
                ),
            ) { Text(label, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun AccountDeletionDialog(
    text: FamilyGamesStrings,
    state: FamilyGamesUiState,
    coordinator: FamilyGamesCoordinator,
) {
    val final = state.accountDeletionConfirmation == AccountDeletionConfirmation.Final
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(if (final) text.deletionFinalTitle else text.deleteAccount) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(if (final) text.deletionFinalExplanation else text.deletionExplanation)
                if (state.accountDeletionFailed) {
                    Spacer(Modifier.height(FamilyGamesSpacing.Md))
                    Text(if (state.accountDeletionAcceptance == null) text.deletionRetry else text.deletionCleanupRetry,
                        color = MaterialTheme.colorScheme.error)
                }
                if (state.busy) Text(text.loading)
            }
        },
        confirmButton = {
            TextButton(enabled = !state.busy, onClick = {
                if (final) coordinator.deleteAccount() else coordinator.confirmAccountDeletionExplanation()
            }) {
                Text(if (final) {
                    if (state.accountDeletionFailed) text.retry else text.deletePermanently
                } else text.continueLabel,
                    color = if (final) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(enabled = !state.busy && state.accountDeletionAcceptance == null,
                onClick = coordinator::cancelAccountDeletion) { Text(text.cancel) }
        },
    )
}

@Composable
private fun RequiredUpdateScreen(
    text: FamilyGamesStrings,
    state: FamilyGamesUiState,
    openExternalUrl: (String) -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(FamilyGamesSpacing.Lg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TemporaryLogo()
            Spacer(Modifier.height(FamilyGamesSpacing.Xl))
            Text(text.updateRequired, fontSize = 30.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            Spacer(Modifier.height(FamilyGamesSpacing.Md))
            Text(
                state.updateMessage ?: text.requiredUpdateMessage,
                color = FamilyGamesColors.Muted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(FamilyGamesSpacing.Lg))
            PrimaryButton(text.updateNow, state.storeDestination != null) {
                state.storeDestination?.let(openExternalUrl)
            }
        }
    }
}

@Composable
private fun UpdateCard(
    title: String,
    message: String,
    action: String,
    dismiss: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = FamilyGamesSpacing.Md),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = FamilyGamesColors.Gold.copy(alpha = .12f)),
    ) {
        Column(Modifier.padding(FamilyGamesSpacing.Md)) {
            Text(title, color = FamilyGamesColors.Gold, fontWeight = FontWeight.Bold)
            Text(message, color = FamilyGamesColors.Muted)
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss) { Text(dismiss) }
                TextButton(onClick = onAction, enabled = actionEnabled) { Text(action) }
            }
        }
    }
}

@Composable
private fun RulesetScreen(text: FamilyGamesStrings, coordinator: FamilyGamesCoordinator) {
    FormPage(text.xoTitle, text.back, coordinator::backHome) {
        RulesetCard(text.classicRules, text.classicDescription, text.included, true, coordinator::showCreateOrJoin)
        Spacer(Modifier.height(FamilyGamesSpacing.Md))
        RulesetCard(text.extendedRules, text.extendedDescription, text.locked, false, {})
    }
}

@Composable
private fun RulesetCard(title: String, subtitle: String, badge: String, enabled: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = if (enabled) FamilyGamesColors.NightSoft else FamilyGamesColors.NightSoft.copy(alpha = .55f)),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(FamilyGamesSpacing.Lg)) {
            val statusPlacement = rulesetCardStatusPlacement(maxWidth)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RulesetModeBadge(if (enabled) "3×3" else "5×5", enabled)
                Spacer(Modifier.width(FamilyGamesSpacing.Md))
                when (statusPlacement) {
                    RulesetCardStatusPlacement.Inline -> Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RulesetDetails(title, subtitle, Modifier.weight(1f))
                        Spacer(Modifier.width(FamilyGamesSpacing.Md))
                        RulesetStatus(
                            badge = badge,
                            enabled = enabled,
                            modifier = Modifier.widthIn(max = RulesetCardStatusMaxWidth),
                        )
                    }

                    RulesetCardStatusPlacement.Below -> Column(Modifier.weight(1f)) {
                        RulesetDetails(title, subtitle)
                        Spacer(Modifier.height(FamilyGamesSpacing.Sm))
                        RulesetStatus(badge, enabled, Modifier.align(Alignment.End))
                    }
                }
            }
        }
    }
}

@Composable
private fun RulesetModeBadge(label: String, enabled: Boolean) {
    Box(
        Modifier
            .size(RulesetCardModeBadgeSize)
            .clip(CircleShape)
            .background(if (enabled) FamilyGamesColors.Purple else Color.DarkGray),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontWeight = FontWeight.Black, maxLines = 1)
    }
}

@Composable
private fun RulesetDetails(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = title,
            fontWeight = FontWeight.Bold,
            fontSize = 19.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = subtitle, color = FamilyGamesColors.Muted)
    }
}

@Composable
private fun RulesetStatus(badge: String, enabled: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = badge,
        modifier = modifier,
        color = if (enabled) FamilyGamesColors.Mint else FamilyGamesColors.Muted,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun CreateJoinScreen(text: FamilyGamesStrings, coordinator: FamilyGamesCoordinator) {
    var code by remember { mutableStateOf("") }
    FormPage(text.xoTitle, text.back, coordinator::showRuleset) {
        PrimaryButton(text.createGame, true, coordinator::createClassicGame)
        Spacer(Modifier.height(FamilyGamesSpacing.Lg))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f), color = FamilyGamesColors.Muted.copy(alpha = .3f))
            Text(text.joinGame, Modifier.padding(horizontal = FamilyGamesSpacing.Md), color = FamilyGamesColors.Muted)
            HorizontalDivider(Modifier.weight(1f), color = FamilyGamesColors.Muted.copy(alpha = .3f))
        }
        Spacer(Modifier.height(FamilyGamesSpacing.Lg))
        OutlinedTextField(
            code,
            { code = it.uppercase().filter(Char::isLetterOrDigit).take(6) },
            label = { Text(text.joinCode) },
            placeholder = { Text(text.joinCodeHint) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(FamilyGamesSpacing.Md))
        OutlinedButton(
            onClick = { coordinator.joinGame(code) },
            enabled = code.length == 6,
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) { Text(text.joinGame, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(FamilyGamesSpacing.Md))
        OutlinedButton(
            onClick = coordinator::showCameraExplanation,
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) { Text(text.scanQr, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun LobbyScreen(text: FamilyGamesStrings, state: FamilyGamesUiState, coordinator: FamilyGamesCoordinator) {
    val game = state.game ?: return
    val membershipId = state.mobileSession?.identity?.membershipId
    val local = game.players.firstOrNull { it.membershipId == membershipId }
    Page {
        PageHeader(text.lobby, text.exit, coordinator::exitGame)
        Spacer(Modifier.height(FamilyGamesSpacing.Lg))
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = FamilyGamesColors.Purple.copy(alpha = .24f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth().padding(FamilyGamesSpacing.Lg), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text.shareCode, color = FamilyGamesColors.Muted)
                Text(game.joinCode, fontSize = 38.sp, fontWeight = FontWeight.Black, letterSpacing = 7.sp)
            }
        }
        Spacer(Modifier.height(FamilyGamesSpacing.Lg))
        game.players.forEach { player -> PlayerCard(player, player.membershipId == membershipId, text) }
        val canInvite = if (game.gameType == "autobus") {
            game.players.size < game.ruleset.playerCount
        } else {
            game.players.size < 2
        }
        if (canInvite) {
            Spacer(Modifier.height(FamilyGamesSpacing.Lg))
            if (game.players.size < 2) {
                Text(text.waitingOpponent, color = FamilyGamesColors.Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(FamilyGamesSpacing.Md))
            }
            Spacer(Modifier.height(FamilyGamesSpacing.Md))
            OutlinedButton(
                onClick = coordinator::showInvitation,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) { Text(text.invitePlayer, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.weight(1f))
        ConnectionPill(
            state.connection,
            state.recoveredFromInterruption,
            text,
            coordinator::retryRealtime,
        )
        OpponentPresenceBanner(state.opponentConnection, text)
        Spacer(Modifier.height(FamilyGamesSpacing.Md))
        PrimaryButton(
            if (local?.isReady == true) text.readyWaiting else text.ready,
            local?.isReady != true && state.connection == RealtimeConnectionState.Connected,
        ) { coordinator.ready() }
    }
}

@Composable
private fun InvitationSurface(
    text: FamilyGamesStrings,
    invitation: GameInvitation,
    invitationQr: @Composable (String, String, Modifier) -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = FamilyGamesColors.NightSoft),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(FamilyGamesSpacing.Lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text.invitationTitle, fontSize = 26.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(FamilyGamesSpacing.Sm))
                Text(text.invitationSubtitle, color = FamilyGamesColors.Muted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(FamilyGamesSpacing.Lg))
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    invitationQr(
                        invitation.deepLink,
                        text.invitationQrDescription,
                        Modifier.size(220.dp).clip(RoundedCornerShape(18.dp)),
                    )
                }
                Spacer(Modifier.height(FamilyGamesSpacing.Lg))
                invitation.joinCode?.let { joinCode ->
                    Text(text.joinCode, color = FamilyGamesColors.Muted)
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(joinCode, fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp)
                    }
                    Spacer(Modifier.height(FamilyGamesSpacing.Sm))
                }
                Text(text.inviteExpiresSoon, color = FamilyGamesColors.Gold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(FamilyGamesSpacing.Lg))
                PrimaryButton(text.shareInvitation, true, onShare)
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(text.close) }
            }
        }
    }
}

@Composable
private fun CameraExplanationDialog(
    text: FamilyGamesStrings,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text.cameraTitle, fontWeight = FontWeight.Black) },
        text = { Text(text.cameraReason) },
        confirmButton = { TextButton(onClick = onContinue) { Text(text.continueCamera) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(text.cancel) } },
    )
}

@Composable
private fun VoiceExplanationDialog(text: FamilyGamesStrings, onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text.voiceTitle, fontWeight = FontWeight.Black) },
        text = { Text(text.voiceReason) },
        confirmButton = { TextButton(onClick = onContinue) { Text(text.enableVoice) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(text.cancel) } },
    )
}

@Composable
private fun VoiceRequestDialog(text: FamilyGamesStrings, onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(text.voiceTitle, fontWeight = FontWeight.Black) },
        text = { Text(text.voiceRequestIncoming) },
        confirmButton = { TextButton(onClick = onAccept) { Text(text.acceptVoice) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(text.declineVoice) } },
    )
}

@Composable
private fun GameplayScreen(text: FamilyGamesStrings, state: FamilyGamesUiState, coordinator: FamilyGamesCoordinator) {
    val game = state.game ?: return
    if (game.gameType == "autobus") {
        AutobusGameplayScreen(text, state, coordinator)
        return
    }
    val membershipId = state.mobileSession?.identity?.membershipId
    val local = game.players.firstOrNull { it.membershipId == membershipId }
    val opponent = game.players.firstOrNull { it.membershipId != membershipId }
    val isLocalTurn = game.activePlayerMembershipId == membershipId
    Page {
        PageHeader(text.xoTitle, text.exit, coordinator::exitGame)
        Spacer(Modifier.height(FamilyGamesSpacing.Md))
        ConnectionPill(
            state.connection,
            state.recoveredFromInterruption,
            text,
            coordinator::retryRealtime,
        )
        if (game.ruleset.voiceEnabled) {
            VoiceControl(text, state, coordinator)
            Spacer(Modifier.height(FamilyGamesSpacing.Sm))
        }
        OpponentPresenceBanner(state.opponentConnection, text)
        Spacer(Modifier.height(FamilyGamesSpacing.Md))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FamilyGamesSpacing.Sm)) {
            PlayerChip(local, text.you, isLocalTurn, Modifier.weight(1f))
            PlayerChip(opponent, text.opponent, !isLocalTurn, Modifier.weight(1f))
        }
        Spacer(Modifier.height(FamilyGamesSpacing.Lg))
        Text(
            if (isLocalTurn) text.yourTurn else text.opponentTurn,
            color = if (isLocalTurn) FamilyGamesColors.Gold else FamilyGamesColors.Muted,
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.CenterHorizontally).animateContentSize(),
        )
        Spacer(Modifier.height(FamilyGamesSpacing.Lg))
        XoBoard(
            game,
            isLocalTurn && !state.busy && state.connection == RealtimeConnectionState.Connected,
            text,
            coordinator::play,
        )
        Spacer(Modifier.height(FamilyGamesSpacing.Lg))
        if (state.connection != RealtimeConnectionState.Connected) {
            Text(
                text.actionUnavailableOffline,
                color = FamilyGamesColors.Gold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(FamilyGamesSpacing.Sm))
        }
    }
}

@Composable
private fun AutobusGameplayScreen(text: FamilyGamesStrings, state: FamilyGamesUiState, coordinator: FamilyGamesCoordinator) {
    val game = state.game ?: return
    val autobus = game.autobus ?: return
    val membershipId = state.mobileSession?.identity?.membershipId.orEmpty()
    Page {
        PageHeader(text.autobusTitle, text.exit, coordinator::exitGame)
        Spacer(Modifier.height(FamilyGamesSpacing.Md))
        ConnectionPill(state.connection, state.recoveredFromInterruption, text, coordinator::retryRealtime)
        OpponentPresenceBanner(state.opponentConnection, text)
        Spacer(Modifier.height(FamilyGamesSpacing.Md))
        if (autobus.phase in setOf("active", "grace")) {
            AutobusRoundStatus(
                text = text,
                autobus = autobus,
                onElapsed = coordinator::finishAutobusRound,
            )
            Spacer(Modifier.height(FamilyGamesSpacing.Md))
        }
        AutobusScores(text, game)
        Spacer(Modifier.height(FamilyGamesSpacing.Lg))
        when (autobus.phase) {
            "active", "grace" -> {
                autobus.categories.forEach { category ->
                    OutlinedTextField(
                        value = state.autobusDrafts[category.key].orEmpty(),
                        onValueChange = { coordinator.updateAutobusAnswer(category.key, it) },
                        label = { Text(categoryLabel(category, state.language)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                    Spacer(Modifier.height(FamilyGamesSpacing.Sm))
                }
                Spacer(Modifier.height(FamilyGamesSpacing.Md))
                PrimaryButton(
                    text.autobusSubmit,
                    state.connection == RealtimeConnectionState.Connected && !state.busy,
                    coordinator::submitAutobusAnswers,
                )
                Spacer(Modifier.height(FamilyGamesSpacing.Sm))
                if (autobus.phase == "active") {
                    OutlinedButton(
                        onClick = coordinator::finishAutobusRound,
                        enabled = state.connection == RealtimeConnectionState.Connected && !state.busy,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                    ) { Text(text.autobusFinish, fontWeight = FontWeight.Bold) }
                }
            }
            "reveal" -> AutobusRevealPanel(text, state, membershipId, coordinator)
            else -> {
                Text(text.loading, color = FamilyGamesColors.Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(FamilyGamesSpacing.Md))
                PrimaryButton(text.autobusReveal, state.connection == RealtimeConnectionState.Connected, coordinator::revealAutobus)
            }
        }
    }
}

@Composable
private fun AutobusRevealPanel(
    text: FamilyGamesStrings,
    state: FamilyGamesUiState,
    membershipId: String,
    coordinator: FamilyGamesCoordinator,
) {
    val game = state.game ?: return
    val autobus = game.autobus ?: return
    val categoryKey = autobus.revealCategoryKey ?: autobus.categories.firstOrNull()?.key ?: return
    val category = autobus.categories.firstOrNull { it.key == categoryKey }
    val pendingAnswers = autobus.answers.filter { it.categoryKey == categoryKey && it.needsVote }
    Text(category?.let { categoryLabel(it, state.language) }.orEmpty(), fontSize = 24.sp, fontWeight = FontWeight.Black)
    Spacer(Modifier.height(FamilyGamesSpacing.Md))
    if (pendingAnswers.isNotEmpty()) {
        AutobusCountdown(
            label = text.autobusVoteRemaining,
            deadlineUtc = autobus.voteDeadlineAtUtc,
            onElapsed = coordinator::revealAutobus,
        )
        Spacer(Modifier.height(FamilyGamesSpacing.Md))
    }
    autobus.answers.filter { it.categoryKey == categoryKey }.forEach { answer ->
        val player = game.players.firstOrNull { it.membershipId == answer.playerMembershipId }
        Surface(
            color = FamilyGamesColors.NightSoft,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = FamilyGamesSpacing.Sm),
        ) {
            Column(Modifier.padding(FamilyGamesSpacing.Md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(player?.displayName.orEmpty(), modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Text("${answer.score}", color = FamilyGamesColors.Gold, fontWeight = FontWeight.Black)
                }
                Text(answer.displayAnswer.ifBlank { "—" }, fontSize = 20.sp, color = FamilyGamesColors.Cream)
                val message = when {
                    answer.needsVote -> text.autobusNeedsVote
                    answer.outcome == "rejected" -> text.autobusRejected
                    answer.duplicate -> "5"
                    else -> "10"
                }
                Text(message, color = if (answer.outcome == "rejected") FamilyGamesColors.Coral else FamilyGamesColors.Muted)
                if (answer.needsVote && answer.playerMembershipId != membershipId) {
                    val hasVoted = autobus.votes.any {
                        it.answerOwnerMembershipId == answer.playerMembershipId &&
                            it.categoryKey == answer.categoryKey &&
                            it.voterMembershipId == membershipId
                    }
                    Spacer(Modifier.height(FamilyGamesSpacing.Sm))
                    if (hasVoted) {
                        Text(text.autobusVoteRecorded, color = FamilyGamesColors.Mint, fontWeight = FontWeight.Bold)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(FamilyGamesSpacing.Sm)) {
                            OutlinedButton(
                                onClick = { coordinator.voteAutobus(answer.playerMembershipId, answer.categoryKey, false) },
                                modifier = Modifier.weight(1f).height(48.dp),
                            ) { Text(text.autobusVoteReject) }
                            Button(
                                onClick = { coordinator.voteAutobus(answer.playerMembershipId, answer.categoryKey, true) },
                                modifier = Modifier.weight(1f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = FamilyGamesColors.Mint, contentColor = FamilyGamesColors.Night),
                            ) { Text(text.autobusVoteAccept, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    }
    autobus.tieMessageCode?.let { messageCode ->
        Text(
            if (messageCode == "autobus_vote_timeout_rejected") text.autobusVoteTimeoutRejected else text.autobusTieRejected,
            color = FamilyGamesColors.Gold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(FamilyGamesSpacing.Md))
    PrimaryButton(
        text.autobusNext,
        pendingAnswers.isEmpty() && state.connection == RealtimeConnectionState.Connected && !state.busy,
        coordinator::revealAutobus,
    )
}

@Composable
private fun AutobusResultScreen(text: FamilyGamesStrings, state: FamilyGamesUiState, coordinator: FamilyGamesCoordinator) {
    val game = state.game ?: return
    val membershipId = state.mobileSession?.identity?.membershipId
    val requester = game.rematchRequestedByMembershipId
    val localAccepted = game.players.firstOrNull { it.membershipId == membershipId }?.isReady == true
    val buttonText = when {
        requester == null -> text.rematch
        requester == membershipId || localAccepted -> text.rematchWaiting
        else -> text.acceptRematch
    }
    Page {
        PageHeader(text.autobusFinalRanking, text.exit, coordinator::exitGame)
        Spacer(Modifier.height(FamilyGamesSpacing.Md))
        ConnectionPill(state.connection, state.recoveredFromInterruption, text, coordinator::retryRealtime)
        Spacer(Modifier.height(FamilyGamesSpacing.Lg))
        AutobusScores(text, game, large = true)
        Spacer(Modifier.weight(1f))
        PrimaryButton(
            buttonText,
            (requester == null || !localAccepted) && state.connection == RealtimeConnectionState.Connected,
        ) {
            if (requester == null) coordinator.requestRematch() else coordinator.acceptRematch()
        }
        Spacer(Modifier.height(FamilyGamesSpacing.Sm))
        OutlinedButton(onClick = coordinator::exitGame, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text(text.exit) }
    }
}

@Composable
private fun AutobusScores(text: FamilyGamesStrings, game: GameSessionSnapshot, large: Boolean = false) {
    val scores = game.autobus?.scores.orEmpty()
    Surface(color = FamilyGamesColors.NightSoft.copy(alpha = .82f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(FamilyGamesSpacing.Md)) {
            Text(text.autobusScores, color = FamilyGamesColors.Muted, fontWeight = FontWeight.Bold)
            val ranking = scores.sortedByDescending { it.score }
            if (large) {
                var displayedRank = 0
                var previousScore: Int? = null
                ranking.forEachIndexed { index, score ->
                    if (previousScore != score.score) displayedRank = index + 1
                    previousScore = score.score
                    val player = game.players.firstOrNull { it.membershipId == score.playerMembershipId }
                    Row(Modifier.fillMaxWidth().padding(top = FamilyGamesSpacing.Sm), verticalAlignment = Alignment.CenterVertically) {
                        Text("$displayedRank", color = FamilyGamesColors.Gold, fontWeight = FontWeight.Black, modifier = Modifier.width(28.dp))
                        Text(player?.displayName.orEmpty(), modifier = Modifier.weight(1f), fontSize = 20.sp)
                        Text("${score.score}", color = FamilyGamesColors.Gold, fontWeight = FontWeight.Black)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = FamilyGamesSpacing.Sm),
                    horizontalArrangement = Arrangement.spacedBy(FamilyGamesSpacing.Md),
                ) {
                    ranking.forEachIndexed { index, score ->
                        val player = game.players.firstOrNull { it.membershipId == score.playerMembershipId }
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${index + 1}  ${player?.displayName.orEmpty()}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text("${score.score}", color = FamilyGamesColors.Gold, fontSize = 22.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutobusRoundStatus(
    text: FamilyGamesStrings,
    autobus: AutobusSnapshot,
    onElapsed: () -> Unit,
) {
    val grace = autobus.phase == "grace"
    val remainingSeconds = rememberRemainingSeconds(
        if (grace) autobus.graceEndsAtUtc else autobus.roundDeadlineAtUtc,
        onElapsed,
    )
    Surface(
        color = if (remainingSeconds <= 5) {
            FamilyGamesColors.Coral.copy(alpha = .18f)
        } else {
            FamilyGamesColors.NightSoft
        },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = FamilyGamesSpacing.Md, vertical = FamilyGamesSpacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(FamilyGamesSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AutobusStatusValue(text.autobusRound, "${autobus.currentRound} / ${autobus.roundCount}", Modifier.weight(1f))
            AutobusStatusValue(text.autobusLetter, autobus.currentLetter.orEmpty(), Modifier.weight(1f), accent = true)
            AutobusStatusValue(
                if (grace) text.autobusGrace else text.autobusTimeRemaining,
                "$remainingSeconds",
                Modifier.weight(1f),
                urgent = remainingSeconds <= 5,
            )
        }
    }
}

@Composable
private fun AutobusStatusValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    urgent: Boolean = false,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = FamilyGamesColors.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                value,
                color = when {
                    urgent -> FamilyGamesColors.Coral
                    accent -> FamilyGamesColors.Gold
                    else -> FamilyGamesColors.Cream
                },
                fontSize = if (accent) 30.sp else 22.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AutobusCountdown(
    label: String,
    deadlineUtc: String?,
    onElapsed: () -> Unit,
) {
    val remainingSeconds = rememberRemainingSeconds(deadlineUtc, onElapsed)
    Surface(
        color = if (remainingSeconds <= 5) FamilyGamesColors.Coral.copy(alpha = .18f) else FamilyGamesColors.Gold.copy(alpha = .14f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = FamilyGamesSpacing.Md, vertical = FamilyGamesSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f), color = FamilyGamesColors.Muted, fontWeight = FontWeight.Bold)
            Text(
                "$remainingSeconds",
                color = if (remainingSeconds <= 5) FamilyGamesColors.Coral else FamilyGamesColors.Gold,
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

@Composable
private fun rememberRemainingSeconds(deadlineUtc: String?, onElapsed: () -> Unit): Int {
    val deadline = remember(deadlineUtc) { deadlineUtc?.let { runCatching { Instant.parse(it) }.getOrNull() } }
    var now by remember(deadlineUtc) { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(deadline) {
        if (deadline == null) return@LaunchedEffect
        while (true) {
            now = Clock.System.now()
            if (now >= deadline) {
                onElapsed()
                break
            }
            delay(250)
        }
    }
    return deadline?.let {
        (((it - now).inWholeMilliseconds.coerceAtLeast(0) + 999) / 1_000).toInt()
    } ?: 0
}

private data class AutobusSetupCategory(
    val key: String,
    val arabic: String,
    val english: String,
    val selectedByDefault: Boolean,
) {
    fun label(language: AppLanguage): String = if (language == AppLanguage.Arabic) arabic else english
}

private val AutobusSetupCategories = listOf(
    AutobusSetupCategory("boy_name", "اسم ولد", "Boy name", true),
    AutobusSetupCategory("girl_name", "اسم بنت", "Girl name", true),
    AutobusSetupCategory("animal", "حيوان", "Animal", true),
    AutobusSetupCategory("plant", "نبات", "Plant", true),
    AutobusSetupCategory("object", "جماد", "Object", true),
    AutobusSetupCategory("country_city", "بلد / مدينة", "Country / city", true),
    AutobusSetupCategory("food", "أكلة", "Food", false),
    AutobusSetupCategory("profession", "مهنة", "Profession", false),
    AutobusSetupCategory("cartoon_character", "شخصية كرتون", "Cartoon character", false),
    AutobusSetupCategory("thing_at_home", "شيء في البيت", "Thing at home", false),
    AutobusSetupCategory("place_to_visit", "مكان نزوره", "Place to visit", false),
)

private fun categoryLabel(category: com.botglobal.lamma.app.data.AutobusCategorySnapshot, language: AppLanguage): String =
    if (language == AppLanguage.Arabic) category.arabicName else category.englishName

@Composable
private fun VoiceControl(text: FamilyGamesStrings, state: FamilyGamesUiState, coordinator: FamilyGamesCoordinator) {
    val voice = state.voice
    val consent = state.voiceConsent
    val label = when (consent.state) {
        VoiceConsentState.Idle -> text.requestVoiceChat
        VoiceConsentState.Requesting -> text.voiceRequestWaiting
        VoiceConsentState.IncomingRequest -> text.voiceRequestIncoming
        VoiceConsentState.Accepted -> text.voiceRequestAccepted
        VoiceConsentState.Declined -> text.voiceRequestDeclined
        VoiceConsentState.TimedOut -> text.voiceRequestTimedOut
        VoiceConsentState.Cancelled -> text.voiceRequestCancelled
        VoiceConsentState.Joining -> text.voiceConnecting
        VoiceConsentState.Connected, VoiceConsentState.Muted -> if (voice.peerMuted) text.opponentMuted else text.voiceConnected
        VoiceConsentState.Reconnecting -> text.voiceReconnecting
        VoiceConsentState.Unavailable -> text.voiceUnavailable
        VoiceConsentState.Ended -> text.requestVoiceChat
    }
    val action = when (consent.state) {
        VoiceConsentState.Idle, VoiceConsentState.Declined, VoiceConsentState.TimedOut,
        VoiceConsentState.Cancelled, VoiceConsentState.Unavailable, VoiceConsentState.Ended -> coordinator::requestVoiceChat
        VoiceConsentState.Requesting -> coordinator::cancelVoiceRequest
        VoiceConsentState.Connected, VoiceConsentState.Muted -> coordinator::toggleVoiceMute
        else -> ({})
    }
    Surface(
        color = FamilyGamesColors.NightSoft.copy(alpha = 0.9f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(
                enabled = consent.state in setOf(
                    VoiceConsentState.Idle, VoiceConsentState.Requesting, VoiceConsentState.Declined,
                    VoiceConsentState.TimedOut, VoiceConsentState.Cancelled, VoiceConsentState.Ended,
                    VoiceConsentState.Unavailable,
                    VoiceConsentState.Connected, VoiceConsentState.Muted,
                ),
                onClick = action,
            ).padding(horizontal = FamilyGamesSpacing.Md, vertical = FamilyGamesSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FamilyGamesSpacing.Sm),
        ) {
            Text(if (voice.muted) "◉" else "●", color = if (consent.state in setOf(VoiceConsentState.Connected, VoiceConsentState.Muted)) FamilyGamesColors.Mint else FamilyGamesColors.Gold)
            Text(label, modifier = Modifier.weight(1f), color = FamilyGamesColors.Cream)
            if (consent.state in setOf(VoiceConsentState.Connected, VoiceConsentState.Muted)) {
                Text(if (voice.muted) text.unmute else text.mute, color = FamilyGamesColors.Gold, fontWeight = FontWeight.Bold)
            } else if (consent.state == VoiceConsentState.Requesting) {
                Text(text.cancelVoiceRequest, color = FamilyGamesColors.Gold, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ResultScreen(text: FamilyGamesStrings, state: FamilyGamesUiState, coordinator: FamilyGamesCoordinator) {
    val game = state.game ?: return
    if (game.gameType == "autobus") {
        AutobusResultScreen(text, state, coordinator)
        return
    }
    val membershipId = state.mobileSession?.identity?.membershipId
    val result = when {
        game.matchStatus == "draw" -> text.draw
        game.winnerMembershipId == membershipId -> text.youWon
        else -> text.opponentWon
    }
    val requester = game.rematchRequestedByMembershipId
    val localAccepted = game.players.firstOrNull { it.membershipId == membershipId }?.isReady == true
    val buttonText = when {
        requester == null -> text.rematch
        requester == membershipId || localAccepted -> text.rematchWaiting
        else -> text.acceptRematch
    }
    Page {
        ConnectionPill(
            state.connection,
            state.recoveredFromInterruption,
            text,
            coordinator::retryRealtime,
        )
        Spacer(Modifier.weight(1f))
        Text(if (game.matchStatus == "draw") "= " else "★", fontSize = 72.sp, color = FamilyGamesColors.Gold, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(FamilyGamesSpacing.Md))
        Text(result, fontSize = 30.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(FamilyGamesSpacing.Lg))
        XoBoard(game, false, text) { _, _ -> }
        Spacer(Modifier.weight(1f))
        PrimaryButton(
            buttonText,
            (requester == null || !localAccepted) && state.connection == RealtimeConnectionState.Connected,
        ) {
            if (requester == null) coordinator.requestRematch() else coordinator.acceptRematch()
        }
        Spacer(Modifier.height(FamilyGamesSpacing.Sm))
        OutlinedButton(onClick = coordinator::exitGame, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text(text.exit) }
    }
}

@Composable
private fun XoBoard(
    game: GameSessionSnapshot,
    enabled: Boolean,
    text: FamilyGamesStrings,
    onCell: (Int, Int) -> Unit,
) {
    val surroundingLayoutDirection = LocalLayoutDirection.current
    val presentation = remember(game.board, game.ruleset, surroundingLayoutDirection) {
        xoBoardPresentation(
            board = game.board,
            boardSize = game.ruleset.boardSize,
            winLength = game.ruleset.winLength,
            surroundingLayoutDirection = surroundingLayoutDirection,
        )
    }
    CompositionLocalProvider(LocalLayoutDirection provides presentation.layoutDirection) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(game.ruleset.boardSize),
            userScrollEnabled = false,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(presentation.cells) { _, cell ->
                val mark = cell.mark
                val scale by animateFloatAsState(if (mark.isBlank()) .94f else 1f)
                val cellDescription = if (mark.isBlank()) text.boardCellEmpty else text.boardCellMarked(mark.uppercase())
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .graphicsLayer(scaleX = scale, scaleY = scale)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            when {
                                cell.index in presentation.winningCellIndexes -> FamilyGamesColors.Gold.copy(alpha = .28f)
                                mark == "x" -> FamilyGamesColors.Purple.copy(alpha = .25f)
                                mark == "o" -> FamilyGamesColors.Coral.copy(alpha = .22f)
                                else -> FamilyGamesColors.NightSoft
                            },
                        )
                        .border(1.dp, FamilyGamesColors.Cream.copy(alpha = .09f), RoundedCornerShape(18.dp))
                        .clickable(enabled = enabled && mark.isBlank()) {
                            onCell(cell.row, cell.column)
                        }
                        .semantics { contentDescription = cellDescription },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        mark.uppercase(),
                        fontSize = if (game.ruleset.boardSize == 3) 48.sp else 28.sp,
                        fontWeight = FontWeight.Black,
                        color = if (mark == "x") FamilyGamesColors.PurpleSoft else FamilyGamesColors.Coral,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerCard(player: PlayerSnapshot, local: Boolean, text: FamilyGamesStrings) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = FamilyGamesSpacing.Sm),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = FamilyGamesColors.NightSoft),
    ) {
        Row(Modifier.padding(FamilyGamesSpacing.Md), verticalAlignment = Alignment.CenterVertically) {
            MarkBadge(player.mark)
            Spacer(Modifier.width(FamilyGamesSpacing.Md))
            Column(Modifier.weight(1f)) {
                Text(if (local) text.you else text.opponent, color = FamilyGamesColors.Muted)
                Text(player.displayName, fontWeight = FontWeight.Bold)
            }
            Text(if (player.isReady) "✓" else "…", color = if (player.isReady) FamilyGamesColors.Mint else FamilyGamesColors.Muted, fontSize = 24.sp)
        }
    }
}

@Composable
private fun PlayerChip(player: PlayerSnapshot?, label: String, active: Boolean, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (active) FamilyGamesColors.Purple.copy(alpha = .3f) else FamilyGamesColors.NightSoft),
    ) {
        Row(Modifier.padding(FamilyGamesSpacing.Md), verticalAlignment = Alignment.CenterVertically) {
            MarkBadge(player?.mark.orEmpty())
            Spacer(Modifier.width(FamilyGamesSpacing.Sm))
            Column {
                Text(label, color = FamilyGamesColors.Muted, fontSize = 12.sp)
                Text(player?.displayName.orEmpty(), fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun MarkBadge(mark: String) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(if (mark == "x") FamilyGamesColors.Purple else FamilyGamesColors.Coral),
        contentAlignment = Alignment.Center,
    ) { Text(mark.uppercase(), fontWeight = FontWeight.Black, fontSize = 22.sp) }
}

@Composable
private fun ConnectionPill(
    state: RealtimeConnectionState,
    recoveredFromInterruption: Boolean,
    text: FamilyGamesStrings,
    retry: () -> Unit,
) {
    val (label, color) = when (state) {
        RealtimeConnectionState.Connected ->
            (if (recoveredFromInterruption) text.recovered else text.connected) to FamilyGamesColors.Mint
        RealtimeConnectionState.Connecting -> text.connecting to FamilyGamesColors.Gold
        RealtimeConnectionState.Reconnecting -> text.reconnecting to FamilyGamesColors.Gold
        else -> text.disconnected to FamilyGamesColors.Coral
    }
    Row(
        Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = .12f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(FamilyGamesSpacing.Sm))
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        if (state == RealtimeConnectionState.Disconnected || state == RealtimeConnectionState.Failed) {
            TextButton(onClick = retry) { Text(text.retry) }
        }
    }
}

@Composable
private fun OpponentPresenceBanner(
    state: OpponentConnectionState,
    text: FamilyGamesStrings,
) {
    AnimatedVisibility(visible = state == OpponentConnectionState.Disconnected) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = FamilyGamesSpacing.Sm),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = FamilyGamesColors.Gold.copy(alpha = .14f)),
        ) {
            Text(
                text.opponentDisconnected,
                modifier = Modifier.fillMaxWidth().padding(FamilyGamesSpacing.Md),
                color = FamilyGamesColors.Gold,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun Page(scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val modifier = Modifier
        .fillMaxSize()
        .imePadding()
        .padding(horizontal = FamilyGamesSpacing.Lg, vertical = FamilyGamesSpacing.Md)
        .let { if (scroll) it.verticalScroll(rememberScrollState()) else it }
    Column(modifier = modifier, content = content)
}

@Composable
private fun FormPage(title: String, back: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Page {
        PageHeader(title, back, onBack)
        Spacer(Modifier.height(FamilyGamesSpacing.Xl))
        content()
    }
}

@Composable
private fun PageHeader(title: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), fontSize = 25.sp, fontWeight = FontWeight.Black)
        TextButton(onClick = onAction) { Text(action, color = FamilyGamesColors.Muted) }
    }
}

@Composable
private fun ColumnScope.TopLanguage(text: FamilyGamesStrings, coordinator: FamilyGamesCoordinator) {
    TextButton(onClick = coordinator::toggleLanguage, modifier = Modifier.align(Alignment.End)) { Text(text.language) }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value,
        onChange,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = FamilyGamesColors.Gold, contentColor = FamilyGamesColors.Night),
    ) { Text(label, fontWeight = FontWeight.Black, fontSize = 17.sp) }
}

@Composable
private fun TemporaryLogo() {
    Box(
        Modifier
            .size(112.dp)
            .rotate(-4f)
            .clip(RoundedCornerShape(32.dp))
            .background(Brush.linearGradient(listOf(FamilyGamesColors.Purple, FamilyGamesColors.Coral)))
            .border(2.dp, FamilyGamesColors.Cream.copy(alpha = .3f), RoundedCornerShape(32.dp)),
        contentAlignment = Alignment.Center,
    ) { Text("X O", fontSize = 32.sp, fontWeight = FontWeight.Black) }
}

@Composable
private fun LoadingOverlay(label: String) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .42f)), contentAlignment = Alignment.Center) {
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = FamilyGamesColors.NightSoft)) {
            Row(Modifier.padding(FamilyGamesSpacing.Lg), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp), color = FamilyGamesColors.Gold, strokeWidth = 3.dp)
                Spacer(Modifier.width(FamilyGamesSpacing.Md))
                Text(label)
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().padding(FamilyGamesSpacing.Md),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = FamilyGamesColors.Coral),
    ) { Text(message, Modifier.padding(FamilyGamesSpacing.Md), color = FamilyGamesColors.Night, fontWeight = FontWeight.Bold) }
}
