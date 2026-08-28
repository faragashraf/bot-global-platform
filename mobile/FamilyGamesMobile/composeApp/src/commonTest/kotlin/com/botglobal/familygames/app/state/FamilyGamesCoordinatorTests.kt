package com.botglobal.familygames.app.state

import com.botglobal.familygames.app.data.FamilyGamesGateway
import com.botglobal.familygames.app.data.GameSessionSnapshot
import com.botglobal.familygames.app.data.MoveRequest
import com.botglobal.familygames.app.data.PlayerSnapshot
import com.botglobal.familygames.app.data.RegistrationRequest
import com.botglobal.familygames.app.data.RulesetSnapshot
import com.botglobal.familygames.app.realtime.GameRealtimeClient
import com.botglobal.familygames.app.realtime.GameRealtimeEvent
import com.botglobal.mobile.platform.device.HapticEvent
import com.botglobal.mobile.platform.device.SemanticHaptics
import com.botglobal.mobile.platform.identity.ApplicationIdentity
import com.botglobal.mobile.platform.identity.IdentityKind
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.realtime.RealtimeConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import com.botglobal.mobile.platform.update.AppVersionPolicy

@OptIn(ExperimentalCoroutinesApi::class)
class FamilyGamesCoordinatorTests {
    @Test
    fun startup_without_session_opens_guest_first_welcome() = runTest {
        val coordinator = FamilyGamesCoordinator(FakeGateway(), FakeRealtime(), SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()
        assertEquals(AppScreen.Welcome, coordinator.state.value.screen)
        coordinator.dispose()
    }

    @Test
    fun startup_blocks_navigation_when_version_is_below_minimum() = runTest {
        val coordinator = FamilyGamesCoordinator(
            FakeGateway(
                policy = AppVersionPolicy(
                    currentVersion = "0.1.0",
                    latestVersion = "0.3.0",
                    minimumSupportedVersion = "0.2.0",
                    message = "Required",
                ),
            ),
            FakeRealtime(),
            SilentHaptics,
            this,
        )
        coordinator.startup()
        advanceUntilIdle()
        assertEquals(AppScreen.RequiredUpdate, coordinator.state.value.screen)
        assertEquals("Required", coordinator.state.value.updateMessage)
        coordinator.dispose()
    }

    @Test
    fun startup_recovers_authoritative_active_game_and_rejoins_realtime() = runTest {
        val gateway = FakeGateway(restored = mobileSession, active = game(version = 3, status = "started"))
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(gateway, realtime, SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()
        assertEquals(AppScreen.Gameplay, coordinator.state.value.screen)
        assertEquals("session-1", realtime.startedSession)
        assertEquals(3, coordinator.state.value.game?.version)
        coordinator.dispose()
    }

    @Test
    fun stale_realtime_snapshot_is_discarded() = runTest {
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(
            FakeGateway(restored = mobileSession, active = game(version = 4, status = "started")),
            realtime,
            SilentHaptics,
            this,
        )
        coordinator.startup()
        advanceUntilIdle()
        realtime.emit(game(version = 2, status = "completed"))
        advanceUntilIdle()
        assertEquals(4, coordinator.state.value.game?.version)
        assertEquals(AppScreen.Gameplay, coordinator.state.value.screen)
        coordinator.dispose()
    }

    @Test
    fun accepted_move_transitions_to_result_from_server_snapshot() = runTest {
        val started = game(version = 4, status = "started", activePlayer = membershipId)
        val completed = started.copy(
            status = "completed",
            version = 5,
            matchStatus = "won",
            winnerMembershipId = membershipId,
        )
        val gateway = FakeGateway(restored = mobileSession, active = started, moveResult = completed)
        val coordinator = FamilyGamesCoordinator(gateway, FakeRealtime(), SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()
        coordinator.play(0, 0)
        advanceUntilIdle()
        assertEquals(4, gateway.lastMove?.expectedVersion)
        assertEquals(AppScreen.Result, coordinator.state.value.screen)
        coordinator.dispose()
    }

    private class FakeGateway(
        private val restored: MobileSession? = null,
        private val active: GameSessionSnapshot? = null,
        private val moveResult: GameSessionSnapshot? = null,
        private val policy: AppVersionPolicy? = null,
    ) : FamilyGamesGateway {
        var lastMove: MoveRequest? = null
        override suspend fun versionPolicy(currentVersion: String, platform: String) =
            policy ?: AppVersionPolicy(currentVersion, currentVersion, currentVersion)
        override suspend fun restore() = restored
        override suspend fun continueAsGuest(displayName: String) = mobileSession
        override suspend fun login(userNameOrEmail: String, password: String) = mobileSession
        override suspend fun register(request: RegistrationRequest) = mobileSession
        override suspend fun logout() = Unit
        override suspend fun activeSession() = active
        override suspend fun createSession(rulesetKey: String) = game()
        override suspend fun joinSession(code: String) = game()
        override suspend fun ready(sessionId: String) = game(status = "started")
        override suspend fun rejoin(sessionId: String) = active ?: game()
        override suspend fun move(request: MoveRequest): GameSessionSnapshot {
            lastMove = request
            return moveResult ?: game(version = request.expectedVersion + 1)
        }
        override suspend fun requestRematch(sessionId: String) = game(status = "completed")
        override suspend fun acceptRematch(sessionId: String) = game(status = "started")
    }

    private class FakeRealtime : GameRealtimeClient {
        private val mutableEvents = MutableSharedFlow<GameRealtimeEvent>(extraBufferCapacity = 4)
        override val connectionState: StateFlow<RealtimeConnectionState> = MutableStateFlow(RealtimeConnectionState.Connected)
        override val events: Flow<GameRealtimeEvent> = mutableEvents
        var startedSession: String? = null
        override suspend fun start(sessionId: String, accessToken: suspend () -> String?) { startedSession = sessionId }
        override suspend fun stop() = Unit
        override suspend fun rejoin() = Unit
        fun emit(snapshot: GameSessionSnapshot) { mutableEvents.tryEmit(GameRealtimeEvent("GameStateUpdated", snapshot)) }
    }

    private object SilentHaptics : SemanticHaptics {
        override fun perform(event: HapticEvent) = Unit
    }

    companion object {
        private const val membershipId = "member-1"
        private val mobileSession = MobileSession(
            "access",
            "2099-01-01T00:00:00Z",
            "refresh",
            "2099-02-01T00:00:00Z",
            ApplicationIdentity(membershipId, "guest:1", "Player", IdentityKind.Guest, "family-games"),
        )

        private fun game(
            version: Long = 0,
            status: String = "waiting",
            activePlayer: String? = null,
        ) = GameSessionSnapshot(
            sessionId = "session-1",
            joinCode = "ABC123",
            gameType = "xo",
            status = status,
            matchNumber = 1,
            ruleset = RulesetSnapshot("classic-3x3", 3, 3, 2, null, true, false),
            players = listOf(
                PlayerSnapshot(membershipId, "Player", 0, "x", true, true),
                PlayerSnapshot("member-2", "Opponent", 1, "o", true, true),
            ),
            board = List(9) { "" },
            version = version,
            activePlayerMembershipId = activePlayer,
            matchStatus = "inprogress",
            lastActivityAtUtc = "2099-01-01T00:00:00Z",
        )
    }
}
