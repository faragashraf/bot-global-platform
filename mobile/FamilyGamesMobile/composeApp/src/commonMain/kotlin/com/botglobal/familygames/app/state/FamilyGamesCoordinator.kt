package com.botglobal.familygames.app.state

import com.botglobal.familygames.app.data.ApiException
import com.botglobal.familygames.app.data.FamilyGamesGateway
import com.botglobal.familygames.app.data.GameSessionSnapshot
import com.botglobal.familygames.app.data.MoveRequest
import com.botglobal.familygames.app.data.RegistrationRequest
import com.botglobal.familygames.app.realtime.GameRealtimeClient
import com.botglobal.mobile.platform.device.HapticEvent
import com.botglobal.mobile.platform.device.PermissionController
import com.botglobal.mobile.platform.device.PermissionKind
import com.botglobal.mobile.platform.device.PermissionState
import com.botglobal.mobile.platform.device.UnavailablePermissionController
import com.botglobal.mobile.platform.device.SemanticHaptics
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.invitations.GameInvitation
import com.botglobal.mobile.platform.invitations.InvitationLinkCodec
import com.botglobal.mobile.platform.invitations.InvitationLinkResult
import com.botglobal.mobile.platform.invitations.InvitationMessageLanguage
import com.botglobal.mobile.platform.invitations.InvitationShareFormatter
import com.botglobal.mobile.platform.invitations.PlatformShareCapability
import com.botglobal.mobile.platform.invitations.QrScanResult
import com.botglobal.mobile.platform.invitations.QrScannerCapability
import com.botglobal.mobile.platform.invitations.UnavailablePlatformShare
import com.botglobal.mobile.platform.invitations.UnavailableQrScanner
import com.botglobal.mobile.platform.realtime.RealtimeConnectionState
import com.botglobal.mobile.platform.update.UpdateMode
import com.botglobal.mobile.platform.update.UpdatePolicyEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class AppScreen {
    Startup,
    Welcome,
    SignIn,
    Register,
    Home,
    Ruleset,
    CreateOrJoin,
    Lobby,
    Gameplay,
    Result,
    RequiredUpdate,
}

enum class AppLanguage { Arabic, English }

data class FamilyGamesUiState(
    val screen: AppScreen = AppScreen.Startup,
    val language: AppLanguage = AppLanguage.Arabic,
    val mobileSession: MobileSession? = null,
    val game: GameSessionSnapshot? = null,
    val connection: RealtimeConnectionState = RealtimeConnectionState.Disconnected,
    val recoveredFromInterruption: Boolean = false,
    val busy: Boolean = false,
    val errorCode: String? = null,
    val optionalUpdateVisible: Boolean = false,
    val updateMessage: String? = null,
    val storeDestination: String? = null,
    val invitation: GameInvitation? = null,
    val cameraExplanationVisible: Boolean = false,
    val pendingInvitationToken: String? = null,
)

class FamilyGamesCoordinator(
    private val gateway: FamilyGamesGateway,
    private val realtime: GameRealtimeClient,
    private val haptics: SemanticHaptics,
    private val scope: CoroutineScope,
    private val currentVersion: String = "0.1.0",
    private val platform: String = "android",
    private val invitationLinks: InvitationLinkCodec = InvitationLinkCodec("familygames://invite"),
    private val platformShare: PlatformShareCapability = UnavailablePlatformShare,
    private val qrScanner: QrScannerCapability = UnavailableQrScanner,
    private val permissions: PermissionController = UnavailablePermissionController,
) {
    private val mutableState = MutableStateFlow(FamilyGamesUiState())
    val state: StateFlow<FamilyGamesUiState> = mutableState.asStateFlow()
    private var realtimeEventsJob: Job? = null
    private var realtimeStateJob: Job? = null
    private var actionJob: Job? = null
    private var realtimeHasConnected = false
    private var realtimeWasInterrupted = false

    fun startup() = launchAction {
        val update = runCatching {
            UpdatePolicyEngine.decide(gateway.versionPolicy(currentVersion, platform))
        }.getOrNull()
        if (update?.mode == UpdateMode.Required) {
            mutableState.update {
                it.copy(
                    screen = AppScreen.RequiredUpdate,
                    updateMessage = update.message,
                    storeDestination = update.storeDestination,
                )
            }
            return@launchAction
        }
        if (update?.mode == UpdateMode.Optional) {
            mutableState.update {
                it.copy(
                    optionalUpdateVisible = true,
                    updateMessage = update.message,
                    storeDestination = update.storeDestination,
                )
            }
        }

        val restored = gateway.restore()
        if (restored == null) {
            mutableState.update { it.copy(screen = AppScreen.Welcome) }
            return@launchAction
        }

        mutableState.update { it.copy(mobileSession = restored) }
        if (resolvePendingInvitationIfAvailable()) return@launchAction
        val active = runCatching { gateway.activeSession() }.getOrNull()
        if (active == null) {
            mutableState.update { it.copy(screen = AppScreen.Home) }
        } else {
            onAuthoritativeSnapshot(active)
            connectRealtime(active.sessionId)
        }
    }

    fun continueAsGuest(displayName: String) = launchAction {
        require(displayName.isNotBlank()) { "display_name_required" }
        val session = gateway.continueAsGuest(displayName.trim())
        mutableState.update { it.copy(mobileSession = session, screen = AppScreen.Home) }
        haptics.perform(HapticEvent.Success)
        resolvePendingInvitationIfAvailable()
    }

    fun signIn(userNameOrEmail: String, password: String) = launchAction {
        val session = gateway.login(userNameOrEmail.trim(), password)
        mutableState.update { it.copy(mobileSession = session, screen = AppScreen.Home) }
        haptics.perform(HapticEvent.Success)
        resolvePendingInvitationIfAvailable()
    }

    fun register(userName: String, email: String, displayName: String, password: String) = launchAction {
        val session = gateway.register(RegistrationRequest(userName, email, displayName, password))
        mutableState.update { it.copy(mobileSession = session, screen = AppScreen.Home) }
        haptics.perform(HapticEvent.Success)
        resolvePendingInvitationIfAvailable()
    }

    fun showSignIn() = navigate(AppScreen.SignIn)
    fun showRegister() = navigate(AppScreen.Register)
    fun showRuleset() = navigate(AppScreen.Ruleset)
    fun showCreateOrJoin() = navigate(AppScreen.CreateOrJoin)
    fun backToWelcome() = navigate(AppScreen.Welcome)
    fun backHome() = navigate(AppScreen.Home)

    fun toggleLanguage() {
        mutableState.update {
            it.copy(language = if (it.language == AppLanguage.Arabic) AppLanguage.English else AppLanguage.Arabic)
        }
    }

    fun dismissOptionalUpdate() {
        mutableState.update { it.copy(optionalUpdateVisible = false) }
    }

    fun createClassicGame() = launchAction {
        val snapshot = gateway.createSession("classic-3x3")
        onAuthoritativeSnapshot(snapshot)
        connectRealtime(snapshot.sessionId)
    }

    fun joinGame(code: String) = launchAction {
        val snapshot = gateway.joinSession(code)
        onAuthoritativeSnapshot(snapshot)
        connectRealtime(snapshot.sessionId)
    }

    fun showInvitation() = launchAction {
        val invitation = gateway.createInvitation(requireGame().sessionId)
        mutableState.update { it.copy(invitation = invitation) }
        haptics.perform(HapticEvent.LightImpact)
    }

    fun dismissInvitation() {
        mutableState.update { it.copy(invitation = null) }
    }

    fun shareInvitation(gameName: String) {
        val invitation = mutableState.value.invitation ?: return
        val language = when (mutableState.value.language) {
            AppLanguage.Arabic -> InvitationMessageLanguage.Arabic
            AppLanguage.English -> InvitationMessageLanguage.English
        }
        if (platformShare.share(
                InvitationShareFormatter.format(
                    language,
                    gameName,
                    invitation.deepLink,
                    invitation.joinCode,
                ),
            )
        ) {
            haptics.perform(HapticEvent.ImportantAction)
        } else {
            mutableState.update { it.copy(errorCode = "share_unavailable") }
            haptics.perform(HapticEvent.Warning)
        }
    }

    fun showCameraExplanation() {
        mutableState.update { it.copy(cameraExplanationVisible = true, errorCode = null) }
    }

    fun dismissCameraExplanation() {
        mutableState.update { it.copy(cameraExplanationVisible = false) }
    }

    fun confirmCameraAndScan(scanPrompt: String) = launchAction {
        mutableState.update { it.copy(cameraExplanationVisible = false) }
        val currentPermission = permissions.state(PermissionKind.Camera)
        val permission = if (currentPermission == PermissionState.Granted) {
            currentPermission
        } else {
            permissions.requestAfterExplanation(PermissionKind.Camera)
        }
        if (permission != PermissionState.Granted) {
            mutableState.update { it.copy(errorCode = "camera_permission_denied") }
            haptics.perform(HapticEvent.Warning)
            return@launchAction
        }

        when (val scan = qrScanner.scan(scanPrompt)) {
            is QrScanResult.Recognized -> {
                haptics.perform(HapticEvent.Selection)
                resolveInvitationCandidate(scan.content)
            }
            QrScanResult.Cancelled -> Unit
            QrScanResult.Unavailable -> {
                mutableState.update { it.copy(errorCode = "qr_scanner_unavailable") }
                haptics.perform(HapticEvent.Warning)
            }
        }
    }

    fun handleInvitationLink(candidate: String) {
        val token = parseInvitationToken(candidate) ?: return
        if (mutableState.value.mobileSession == null) {
            mutableState.update {
                it.copy(
                    pendingInvitationToken = token,
                    screen = if (it.screen == AppScreen.Startup) it.screen else AppScreen.Welcome,
                    errorCode = null,
                )
            }
            return
        }
        launchAction { resolveInvitation(token) }
    }

    fun ready() = launchAction {
        val game = requireGame()
        onAuthoritativeSnapshot(gateway.ready(game.sessionId))
    }

    fun play(row: Int, column: Int) = launchAction {
        val game = requireGame()
        val identity = mutableState.value.mobileSession?.identity ?: error("session_missing")
        if (game.status != "started" || game.activePlayerMembershipId != identity.membershipId) {
            haptics.perform(HapticEvent.Warning)
            return@launchAction
        }
        val result = try {
            gateway.move(
                MoveRequest(
                    game.sessionId,
                    commandId(),
                    row,
                    column,
                    game.version,
                ),
            )
        } catch (error: ApiException) {
            if (error.code !in AuthoritativeRefreshErrors) throw error
            val recovered = gateway.rejoin(game.sessionId)
            onAuthoritativeSnapshot(recovered, recoveredFromInterruption = true)
            mutableState.update { it.copy(errorCode = error.code) }
            haptics.perform(HapticEvent.Warning)
            return@launchAction
        }
        onAuthoritativeSnapshot(result)
        if (result.status != "completed") haptics.perform(HapticEvent.GameEvent)
    }

    fun requestRematch() = launchAction {
        onAuthoritativeSnapshot(gateway.requestRematch(requireGame().sessionId))
        haptics.perform(HapticEvent.ImportantAction)
    }

    fun acceptRematch() = launchAction {
        onAuthoritativeSnapshot(gateway.acceptRematch(requireGame().sessionId))
        haptics.perform(HapticEvent.Success)
    }

    fun retryRealtime() = launchAction {
        val sessionId = requireGame().sessionId
        connectRealtime(sessionId)
        onAuthoritativeSnapshot(
            gateway.rejoin(sessionId),
            recoveredFromInterruption = true,
        )
    }

    fun resumeAfterForeground() {
        val sessionId = mutableState.value.game?.sessionId ?: return
        if (mutableState.value.screen !in GameScreens) return
        launchAction {
            val snapshot = try {
                realtime.rejoin()
                gateway.rejoin(sessionId)
            } catch (_: Throwable) {
                connectRealtime(sessionId)
                gateway.rejoin(sessionId)
            }
            onAuthoritativeSnapshot(
                snapshot,
                recoveredFromInterruption = true,
            )
        }
    }

    fun exitGame() = launchAction {
        realtime.stop()
        mutableState.update { it.copy(game = null, invitation = null, screen = AppScreen.Home) }
    }

    fun logout() = launchAction {
        realtime.stop()
        gateway.logout()
        mutableState.value = FamilyGamesUiState(screen = AppScreen.Welcome, language = mutableState.value.language)
    }

    fun dispose() {
        actionJob?.cancel()
        realtimeEventsJob?.cancel()
        realtimeStateJob?.cancel()
        realtimeHasConnected = false
        realtimeWasInterrupted = false
        scope.launch { realtime.stop() }
    }

    private fun navigate(screen: AppScreen) {
        mutableState.update { it.copy(screen = screen, errorCode = null) }
    }

    private suspend fun connectRealtime(sessionId: String) {
        realtimeEventsJob?.cancel()
        realtimeStateJob?.cancel()
        realtimeEventsJob = scope.launch {
            realtime.events.collectLatest { event -> onAuthoritativeSnapshot(event.snapshot) }
        }
        realtimeStateJob = scope.launch {
            realtime.connectionState.collectLatest { connection ->
                when (connection) {
                    RealtimeConnectionState.Reconnecting,
                    RealtimeConnectionState.Failed,
                    -> if (realtimeHasConnected) realtimeWasInterrupted = true
                    else -> Unit
                }
                mutableState.update {
                    it.copy(
                        connection = connection,
                        recoveredFromInterruption = if (connection == RealtimeConnectionState.Connected) {
                            it.recoveredFromInterruption
                        } else {
                            false
                        },
                    )
                }
                if (connection == RealtimeConnectionState.Connected) {
                    if (realtimeWasInterrupted) recoverAuthoritativeState(sessionId)
                    realtimeHasConnected = true
                    realtimeWasInterrupted = false
                }
            }
        }
        realtime.start(sessionId) { gateway.restore()?.accessToken }
    }

    private suspend fun recoverAuthoritativeState(sessionId: String) {
        try {
            onAuthoritativeSnapshot(
                gateway.rejoin(sessionId),
                recoveredFromInterruption = true,
            )
        } catch (_: Throwable) {
            mutableState.update { it.copy(errorCode = "recovery_failed") }
            haptics.perform(HapticEvent.Error)
        }
    }

    private suspend fun resolveInvitationCandidate(candidate: String) {
        val token = parseInvitationToken(candidate) ?: return
        if (mutableState.value.mobileSession == null) {
            mutableState.update {
                it.copy(
                    pendingInvitationToken = token,
                    screen = AppScreen.Welcome,
                    errorCode = null,
                )
            }
            return
        }
        resolveInvitation(token)
    }

    private fun parseInvitationToken(candidate: String): String? =
        when (val parsed = invitationLinks.parse(candidate)) {
            is InvitationLinkResult.Valid -> parsed.token
            InvitationLinkResult.Invalid -> {
                mutableState.update { it.copy(errorCode = "invitation_invalid") }
                haptics.perform(HapticEvent.Error)
                null
            }
        }

    private suspend fun resolvePendingInvitationIfAvailable(): Boolean {
        val token = mutableState.value.pendingInvitationToken ?: return false
        resolveInvitation(token)
        return true
    }

    private suspend fun resolveInvitation(token: String) {
        val snapshot = gateway.resolveInvitation(token)
        mutableState.update {
            it.copy(
                pendingInvitationToken = null,
                invitation = null,
                errorCode = null,
            )
        }
        onAuthoritativeSnapshot(snapshot)
        connectRealtime(snapshot.sessionId)
        haptics.perform(HapticEvent.Success)
    }

    private fun onAuthoritativeSnapshot(
        snapshot: GameSessionSnapshot,
        recoveredFromInterruption: Boolean = false,
    ) {
        val current = mutableState.value.game
        if (current != null && current.sessionId == snapshot.sessionId) {
            if (snapshot.matchNumber < current.matchNumber) return
            if (snapshot.matchNumber == current.matchNumber && snapshot.version < current.version) return
        }
        val screen = when (snapshot.status) {
            "completed" -> AppScreen.Result
            "started" -> AppScreen.Gameplay
            else -> AppScreen.Lobby
        }
        emitGameFeedback(current, snapshot)
        mutableState.update {
            it.copy(
                game = snapshot,
                screen = screen,
                errorCode = null,
                recoveredFromInterruption = recoveredFromInterruption,
            )
        }
    }

    private fun emitGameFeedback(current: GameSessionSnapshot?, snapshot: GameSessionSnapshot) {
        if (current == null || current.sessionId != snapshot.sessionId) return
        if (snapshot.matchNumber == current.matchNumber && snapshot.version <= current.version) return
        val localMembershipId = mutableState.value.mobileSession?.identity?.membershipId
        when {
            snapshot.status == "completed" && snapshot.matchStatus == "draw" ->
                haptics.perform(HapticEvent.LightImpact)
            snapshot.status == "completed" && snapshot.winnerMembershipId == localMembershipId ->
                haptics.perform(HapticEvent.Success)
            snapshot.status == "completed" -> haptics.perform(HapticEvent.Warning)
            current.activePlayerMembershipId != localMembershipId &&
                snapshot.activePlayerMembershipId == localMembershipId ->
                haptics.perform(HapticEvent.Selection)
        }
    }

    private fun requireGame(): GameSessionSnapshot =
        mutableState.value.game ?: error("game_session_missing")

    private fun launchAction(action: suspend () -> Unit) {
        if (actionJob?.isActive == true) return
        actionJob = scope.launch {
            mutableState.update { it.copy(busy = true, errorCode = null) }
            try {
                action()
            } catch (error: ApiException) {
                mutableState.update { it.copy(errorCode = error.code) }
                haptics.perform(HapticEvent.Error)
            } catch (error: Throwable) {
                mutableState.update { it.copy(errorCode = "unexpected_error") }
                haptics.perform(HapticEvent.Error)
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun commandId(): String = buildString(32) {
        repeat(32) { append("0123456789abcdef"[Random.nextInt(16)]) }
    }

    private companion object {
        val GameScreens = setOf(AppScreen.Lobby, AppScreen.Gameplay, AppScreen.Result)
        val AuthoritativeRefreshErrors = setOf(
            "stale_version",
            "duplicate_command",
            "concurrent_move",
            "duplicate_or_concurrent_move",
            "game_completed",
        )
    }
}
