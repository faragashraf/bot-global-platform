package com.botglobal.familygames.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.botglobal.familygames.app.data.FamilyGamesApi
import com.botglobal.familygames.app.data.GameSessionSnapshot
import com.botglobal.familygames.app.data.PlayerSnapshot
import com.botglobal.familygames.app.data.createPlatformHttpClient
import com.botglobal.familygames.app.realtime.createGameRealtimeClient
import com.botglobal.familygames.app.state.AppLanguage
import com.botglobal.familygames.app.state.AppScreen
import com.botglobal.familygames.app.state.FamilyGamesCoordinator
import com.botglobal.familygames.app.state.FamilyGamesUiState
import com.botglobal.mobile.platform.device.SemanticHaptics
import com.botglobal.mobile.platform.identity.SessionVault
import com.botglobal.mobile.platform.realtime.RealtimeConnectionState
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
) {
    val scope = rememberCoroutineScope()
    val coordinator = remember(apiBaseUrl, sessionVault, haptics) {
        val gateway = FamilyGamesApi(createPlatformHttpClient(), apiBaseUrl.trimEnd('/'), sessionVault)
        FamilyGamesCoordinator(
            gateway,
            createGameRealtimeClient(apiBaseUrl.trimEnd('/')),
            haptics,
            scope,
            appVersion,
            platform,
        )
    }
    val state by coordinator.state.collectAsState()
    val text = strings(state.language)
    val direction = if (state.language == AppLanguage.Arabic) LayoutDirection.Rtl else LayoutDirection.Ltr

    LaunchedEffect(coordinator) { coordinator.startup() }
    LaunchedEffect(coordinator, foregroundEvents) {
        foregroundEvents.collect { coordinator.resumeAfterForeground() }
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
                        AppScreen.Home -> HomeScreen(text, state, coordinator, openExternalUrl)
                        AppScreen.Ruleset -> RulesetScreen(text, coordinator)
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
                    if (state.busy) LoadingOverlay(text.loading)
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
        TopLanguage(text, coordinator)
        Spacer(Modifier.weight(1f))
        TemporaryLogo()
        Spacer(Modifier.height(FamilyGamesSpacing.Lg))
        Text(text.appName, fontSize = 36.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        Text(text.tagline, color = FamilyGamesColors.Muted, fontSize = 18.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(FamilyGamesSpacing.Xl))
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it.take(40) },
            label = { Text(text.displayName) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Spacer(Modifier.height(FamilyGamesSpacing.Md))
        PrimaryButton(text.continueGuest, displayName.isNotBlank() && !state.busy) {
            coordinator.continueAsGuest(displayName)
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
        Spacer(Modifier.weight(1f))
        TextButton(onClick = coordinator::logout, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(text.logout, color = FamilyGamesColors.Muted)
        }
    }
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
        Row(Modifier.padding(FamilyGamesSpacing.Lg), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(58.dp).clip(CircleShape).background(if (enabled) FamilyGamesColors.Purple else Color.DarkGray),
                contentAlignment = Alignment.Center,
            ) { Text(if (enabled) "3×3" else "5×5", fontWeight = FontWeight.Black) }
            Spacer(Modifier.width(FamilyGamesSpacing.Md))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text(subtitle, color = FamilyGamesColors.Muted)
            }
            Text(badge, color = if (enabled) FamilyGamesColors.Mint else FamilyGamesColors.Muted, fontWeight = FontWeight.Bold)
        }
    }
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
        if (game.players.size < 2) {
            Spacer(Modifier.height(FamilyGamesSpacing.Lg))
            Text(text.waitingOpponent, color = FamilyGamesColors.Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        Spacer(Modifier.weight(1f))
        ConnectionPill(
            state.connection,
            state.recoveredFromInterruption,
            text,
            coordinator::retryRealtime,
        )
        Spacer(Modifier.height(FamilyGamesSpacing.Md))
        PrimaryButton(
            if (local?.isReady == true) text.readyWaiting else text.ready,
            local?.isReady != true && state.connection == RealtimeConnectionState.Connected,
        ) { coordinator.ready() }
    }
}

@Composable
private fun GameplayScreen(text: FamilyGamesStrings, state: FamilyGamesUiState, coordinator: FamilyGamesCoordinator) {
    val game = state.game ?: return
    val membershipId = state.mobileSession?.identity?.membershipId
    val local = game.players.firstOrNull { it.membershipId == membershipId }
    val opponent = game.players.firstOrNull { it.membershipId != membershipId }
    val isLocalTurn = game.activePlayerMembershipId == membershipId
    Page(scroll = false) {
        PageHeader(text.xoTitle, text.exit, coordinator::exitGame)
        Spacer(Modifier.height(FamilyGamesSpacing.Md))
        ConnectionPill(
            state.connection,
            state.recoveredFromInterruption,
            text,
            coordinator::retryRealtime,
        )
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
        Text(text.voiceUnavailable, color = FamilyGamesColors.Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun ResultScreen(text: FamilyGamesStrings, state: FamilyGamesUiState, coordinator: FamilyGamesCoordinator) {
    val game = state.game ?: return
    val membershipId = state.mobileSession?.identity?.membershipId
    val result = when {
        game.matchStatus == "draw" -> text.draw
        game.winnerMembershipId == membershipId -> text.youWon
        else -> text.opponentWon
    }
    val requester = game.rematchRequestedByMembershipId
    val buttonText = when {
        requester == null -> text.rematch
        requester == membershipId -> text.rematchWaiting
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
            requester != membershipId && state.connection == RealtimeConnectionState.Connected,
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
