package com.botglobal.familygames.app.state

import com.botglobal.familygames.app.data.ApiException
import com.botglobal.familygames.app.data.FamilyGamesGateway
import com.botglobal.familygames.app.data.GameSessionSnapshot
import com.botglobal.familygames.app.data.MoveRequest
import com.botglobal.familygames.app.data.RegistrationRequest
import com.botglobal.familygames.app.realtime.GameRealtimeClient
import com.botglobal.mobile.platform.device.HapticEvent
import com.botglobal.mobile.platform.device.SemanticHaptics
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.realtime.RealtimeConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random
import com.botglobal.mobile.platform.update.UpdateMode
import com.botglobal.mobile.platform.update.UpdatePolicyEngine

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
    val busy: Boolean = false,
    val errorCode: String? = null,
    val optionalUpdateVisible: Boolean = false,
    val updateMessage: String? = null,
    val storeDestination: String? = null,
)

class FamilyGamesCoordinator(
    private val gateway: FamilyGamesGateway,
    private val realtime: GameRealtimeClient,
    private val haptics: SemanticHaptics,
    private val scope: CoroutineScope,
    private val currentVersion: String = "0.1.0",
    private val platform: String = "android",
) {
    private val mutableState = MutableStateFlow(FamilyGamesUiState())
    val state: StateFlow<FamilyGamesUiState> = mutableState.asStateFlow()
    private var realtimeEventsJob: Job? = null
    private var realtimeStateJob: Job? = null

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
    }

    fun signIn(userNameOrEmail: String, password: String) = launchAction {
        val session = gateway.login(userNameOrEmail.trim(), password)
        mutableState.update { it.copy(mobileSession = session, screen = AppScreen.Home) }
        haptics.perform(HapticEvent.Success)
    }

    fun register(userName: String, email: String, displayName: String, password: String) = launchAction {
        val session = gateway.register(RegistrationRequest(userName, email, displayName, password))
        mutableState.update { it.copy(mobileSession = session, screen = AppScreen.Home) }
        haptics.perform(HapticEvent.Success)
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
        val result = gateway.move(
            MoveRequest(
                game.sessionId,
                commandId(),
                row,
                column,
                game.version,
            ),
        )
        onAuthoritativeSnapshot(result)
        haptics.perform(if (result.status == "completed") HapticEvent.Success else HapticEvent.GameEvent)
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
        onAuthoritativeSnapshot(gateway.rejoin(sessionId))
    }

    fun exitGame() = launchAction {
        realtime.stop()
        mutableState.update { it.copy(game = null, screen = AppScreen.Home) }
    }

    fun logout() = launchAction {
        realtime.stop()
        gateway.logout()
        mutableState.value = FamilyGamesUiState(screen = AppScreen.Welcome, language = mutableState.value.language)
    }

    fun dispose() {
        realtimeEventsJob?.cancel()
        realtimeStateJob?.cancel()
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
                mutableState.update { it.copy(connection = connection) }
            }
        }
        realtime.start(sessionId) { gateway.restore()?.accessToken }
    }

    private fun onAuthoritativeSnapshot(snapshot: GameSessionSnapshot) {
        val current = mutableState.value.game
        if (current != null && current.sessionId == snapshot.sessionId && snapshot.version < current.version) return
        val screen = when (snapshot.status) {
            "completed" -> AppScreen.Result
            "started" -> AppScreen.Gameplay
            else -> AppScreen.Lobby
        }
        mutableState.update { it.copy(game = snapshot, screen = screen, errorCode = null) }
    }

    private fun requireGame(): GameSessionSnapshot =
        mutableState.value.game ?: error("game_session_missing")

    private fun launchAction(action: suspend () -> Unit) {
        if (mutableState.value.busy) return
        scope.launch {
            mutableState.update { it.copy(busy = true, errorCode = null) }
            try {
                action()
            } catch (error: ApiException) {
                mutableState.update { it.copy(errorCode = error.code) }
                haptics.perform(HapticEvent.Error)
            } catch (error: Throwable) {
                mutableState.update { it.copy(errorCode = error.message ?: "unexpected_error") }
                haptics.perform(HapticEvent.Error)
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun commandId(): String = buildString(32) {
        repeat(32) { append("0123456789abcdef"[Random.nextInt(16)]) }
    }
}
