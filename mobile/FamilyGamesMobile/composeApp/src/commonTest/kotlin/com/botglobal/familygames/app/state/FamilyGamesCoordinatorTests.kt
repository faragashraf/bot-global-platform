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
import com.botglobal.mobile.platform.invitations.GameInvitation
import com.botglobal.mobile.platform.invitations.QrScanResult
import com.botglobal.mobile.platform.invitations.QrScannerCapability
import com.botglobal.mobile.platform.device.PermissionController
import com.botglobal.mobile.platform.device.PermissionKind
import com.botglobal.mobile.platform.device.PermissionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import com.botglobal.familygames.app.data.ApiException
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
    fun duplicate_guest_taps_create_only_one_membership_request() = runTest {
        val gateway = FakeGateway()
        val coordinator = FamilyGamesCoordinator(gateway, FakeRealtime(), SilentHaptics, this)

        coordinator.continueAsGuest("Player")
        coordinator.continueAsGuest("Player")
        advanceUntilIdle()

        assertEquals(1, gateway.guestCalls)
        assertEquals(AppScreen.Home, coordinator.state.value.screen)
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
        val haptics = RecordingHaptics()
        val coordinator = FamilyGamesCoordinator(gateway, FakeRealtime(), haptics, this)
        coordinator.startup()
        advanceUntilIdle()
        coordinator.play(0, 0)
        advanceUntilIdle()
        assertEquals(4, gateway.lastMove?.expectedVersion)
        assertEquals(AppScreen.Result, coordinator.state.value.screen)
        assertContains(haptics.events, HapticEvent.Success)
        coordinator.dispose()
    }

    @Test
    fun reconnect_fetches_authoritative_state_and_marks_game_recovered() = runTest {
        val gateway = FakeGateway(
            restored = mobileSession,
            active = game(version = 3, status = "started"),
            rejoinResult = game(version = 5, status = "started", activePlayer = membershipId),
        )
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(gateway, realtime, SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()

        realtime.setConnection(RealtimeConnectionState.Reconnecting)
        advanceUntilIdle()
        realtime.setConnection(RealtimeConnectionState.Connected)
        advanceUntilIdle()

        assertEquals(1, gateway.rejoinCalls)
        assertEquals(5, coordinator.state.value.game?.version)
        assertEquals(true, coordinator.state.value.recoveredFromInterruption)
        coordinator.dispose()
    }

    @Test
    fun foreground_resume_rejoins_and_refreshes_authoritative_state() = runTest {
        val gateway = FakeGateway(
            restored = mobileSession,
            active = game(version = 3, status = "started"),
            rejoinResult = game(version = 7, status = "started", activePlayer = membershipId),
        )
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(gateway, realtime, SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()

        coordinator.resumeAfterForeground()
        advanceUntilIdle()

        assertEquals(1, realtime.rejoinCalls)
        assertEquals(1, gateway.rejoinCalls)
        assertEquals(7, coordinator.state.value.game?.version)
        assertEquals(true, coordinator.state.value.recoveredFromInterruption)
        coordinator.dispose()
    }

    @Test
    fun stale_move_rejection_refreshes_authoritative_state_without_optimistic_override() = runTest {
        val gateway = FakeGateway(
            restored = mobileSession,
            active = game(version = 4, status = "started", activePlayer = membershipId),
            rejoinResult = game(version = 6, status = "started", activePlayer = membershipId),
            moveError = ApiException("stale_version", 409, "Server detail must not reach the UI."),
        )
        val haptics = RecordingHaptics()
        val coordinator = FamilyGamesCoordinator(gateway, FakeRealtime(), haptics, this)
        coordinator.startup()
        advanceUntilIdle()
        coordinator.play(0, 0)
        advanceUntilIdle()

        assertEquals(1, gateway.rejoinCalls)
        assertEquals(6, coordinator.state.value.game?.version)
        assertEquals("stale_version", coordinator.state.value.errorCode)
        assertContains(haptics.events, HapticEvent.Warning)
        coordinator.dispose()
    }

    @Test
    fun newer_match_with_reset_version_replaces_completed_previous_match() = runTest {
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(
            FakeGateway(restored = mobileSession, active = game(version = 6, status = "completed")),
            realtime,
            SilentHaptics,
            this,
        )
        coordinator.startup()
        advanceUntilIdle()

        realtime.emit(game(version = 0, status = "started", matchNumber = 2, activePlayer = membershipId))
        advanceUntilIdle()

        assertEquals(2, coordinator.state.value.game?.matchNumber)
        assertEquals(0, coordinator.state.value.game?.version)
        assertEquals(AppScreen.Gameplay, coordinator.state.value.screen)
        coordinator.dispose()
    }

    @Test
    fun delayed_snapshot_from_previous_match_is_discarded() = runTest {
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(
            FakeGateway(
                restored = mobileSession,
                active = game(version = 1, status = "started", matchNumber = 2),
            ),
            realtime,
            SilentHaptics,
            this,
        )
        coordinator.startup()
        advanceUntilIdle()

        realtime.emit(game(version = 9, status = "completed", matchNumber = 1))
        advanceUntilIdle()

        assertEquals(2, coordinator.state.value.game?.matchNumber)
        assertEquals(1, coordinator.state.value.game?.version)
        assertEquals(AppScreen.Gameplay, coordinator.state.value.screen)
        coordinator.dispose()
    }

    @Test
    fun host_can_create_reusable_invitation_presentation_state() = runTest {
        val gateway = FakeGateway(restored = mobileSession, active = game())
        val coordinator = FamilyGamesCoordinator(gateway, FakeRealtime(), SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()

        coordinator.showInvitation()
        advanceUntilIdle()

        assertEquals("invite-1", coordinator.state.value.invitation?.invitationId)
        assertEquals("ABC123", coordinator.state.value.invitation?.joinCode)
        coordinator.dispose()
    }

    @Test
    fun valid_deep_link_resolves_authoritatively_and_opens_joined_lobby() = runTest {
        val gateway = FakeGateway(restored = mobileSession, resolvedInvitation = game())
        val coordinator = FamilyGamesCoordinator(gateway, FakeRealtime(), SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()

        coordinator.handleInvitationLink("familygames://invite/AbCdEf0123456789_opaque-token")
        advanceUntilIdle()

        assertEquals("AbCdEf0123456789_opaque-token", gateway.resolvedToken)
        assertEquals(AppScreen.Lobby, coordinator.state.value.screen)
        assertEquals("session-1", coordinator.state.value.game?.sessionId)
        coordinator.dispose()
    }

    @Test
    fun invalid_qr_never_reaches_backend_and_exposes_semantic_error() = runTest {
        val gateway = FakeGateway(restored = mobileSession)
        val coordinator = FamilyGamesCoordinator(gateway, FakeRealtime(), SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()

        coordinator.handleInvitationLink("https://attacker.invalid/invite/token")
        advanceUntilIdle()

        assertEquals(null, gateway.resolvedToken)
        assertEquals("invitation_invalid", coordinator.state.value.errorCode)
        coordinator.dispose()
    }

    @Test
    fun cold_start_invitation_is_retained_until_secure_session_restoration() = runTest {
        val gateway = FakeGateway(restored = mobileSession, resolvedInvitation = game())
        val coordinator = FamilyGamesCoordinator(gateway, FakeRealtime(), SilentHaptics, this)

        coordinator.handleInvitationLink("familygames://invite/AbCdEf0123456789_opaque-token")
        coordinator.startup()
        advanceUntilIdle()

        assertEquals("AbCdEf0123456789_opaque-token", gateway.resolvedToken)
        assertEquals(null, coordinator.state.value.pendingInvitationToken)
        assertEquals(AppScreen.Lobby, coordinator.state.value.screen)
        coordinator.dispose()
    }

    @Test
    fun camera_permission_is_requested_only_after_explanation_confirmation() = runTest {
        val gateway = FakeGateway(restored = mobileSession, resolvedInvitation = game())
        val permissions = RecordingCameraPermission()
        val scanner = RecognizingScanner("familygames://invite/AbCdEf0123456789_opaque-token")
        val coordinator = FamilyGamesCoordinator(
            gateway = gateway,
            realtime = FakeRealtime(),
            haptics = SilentHaptics,
            scope = this,
            qrScanner = scanner,
            permissions = permissions,
        )
        coordinator.startup()
        advanceUntilIdle()

        coordinator.showCameraExplanation()
        assertEquals(0, permissions.requests)
        assertEquals(true, coordinator.state.value.cameraExplanationVisible)

        coordinator.confirmCameraAndScan("Scan invitation")
        advanceUntilIdle()

        assertEquals(1, permissions.requests)
        assertEquals(1, scanner.scans)
        assertEquals("AbCdEf0123456789_opaque-token", gateway.resolvedToken)
        coordinator.dispose()
    }

    private class FakeGateway(
        private val restored: MobileSession? = null,
        private val active: GameSessionSnapshot? = null,
        private val moveResult: GameSessionSnapshot? = null,
        private val policy: AppVersionPolicy? = null,
        private val rejoinResult: GameSessionSnapshot? = null,
        private val moveError: ApiException? = null,
        private val resolvedInvitation: GameSessionSnapshot? = null,
    ) : FamilyGamesGateway {
        var lastMove: MoveRequest? = null
        var rejoinCalls: Int = 0
        var guestCalls: Int = 0
        var resolvedToken: String? = null
        override suspend fun versionPolicy(currentVersion: String, platform: String) =
            policy ?: AppVersionPolicy(currentVersion, currentVersion, currentVersion)
        override suspend fun restore() = restored
        override suspend fun continueAsGuest(displayName: String): MobileSession {
            guestCalls++
            return mobileSession
        }
        override suspend fun login(userNameOrEmail: String, password: String) = mobileSession
        override suspend fun register(request: RegistrationRequest) = mobileSession
        override suspend fun logout() = Unit
        override suspend fun activeSession() = active
        override suspend fun createSession(rulesetKey: String) = game()
        override suspend fun joinSession(code: String) = game()
        override suspend fun createInvitation(sessionId: String) = GameInvitation(
            invitationId = "invite-1",
            sessionReference = sessionId,
            gameType = "xo",
            invitationToken = "AbCdEf0123456789_opaque-token",
            expiresAtUtc = "2099-01-01T00:10:00Z",
            inviterDisplayName = "Player",
            deepLink = "familygames://invite/AbCdEf0123456789_opaque-token",
            joinCode = "ABC123",
        )
        override suspend fun resolveInvitation(token: String): GameSessionSnapshot {
            resolvedToken = token
            return resolvedInvitation ?: game()
        }
        override suspend fun ready(sessionId: String) = game(status = "started")
        override suspend fun rejoin(sessionId: String): GameSessionSnapshot {
            rejoinCalls++
            return rejoinResult ?: active ?: game()
        }
        override suspend fun move(request: MoveRequest): GameSessionSnapshot {
            lastMove = request
            moveError?.let { throw it }
            return moveResult ?: game(version = request.expectedVersion + 1)
        }
        override suspend fun requestRematch(sessionId: String) = game(status = "completed")
        override suspend fun acceptRematch(sessionId: String) = game(status = "started")
    }

    private class FakeRealtime : GameRealtimeClient {
        private val mutableEvents = MutableSharedFlow<GameRealtimeEvent>(extraBufferCapacity = 4)
        private val mutableConnection = MutableStateFlow(RealtimeConnectionState.Connected)
        override val connectionState: StateFlow<RealtimeConnectionState> = mutableConnection
        override val events: Flow<GameRealtimeEvent> = mutableEvents
        var startedSession: String? = null
        var rejoinCalls: Int = 0
        override suspend fun start(sessionId: String, accessToken: suspend () -> String?) { startedSession = sessionId }
        override suspend fun stop() = Unit
        override suspend fun rejoin() { rejoinCalls++ }
        fun emit(snapshot: GameSessionSnapshot) { mutableEvents.tryEmit(GameRealtimeEvent("GameStateUpdated", snapshot)) }
        fun setConnection(state: RealtimeConnectionState) { mutableConnection.value = state }
    }

    private object SilentHaptics : SemanticHaptics {
        override fun perform(event: HapticEvent) = Unit
    }

    private class RecordingHaptics : SemanticHaptics {
        val events = mutableListOf<HapticEvent>()
        override fun perform(event: HapticEvent) { events += event }
    }

    private class RecordingCameraPermission : PermissionController {
        var requests = 0
        override suspend fun state(permission: PermissionKind) = PermissionState.Unknown
        override suspend fun requestAfterExplanation(permission: PermissionKind): PermissionState {
            requests++
            return PermissionState.Granted
        }
    }

    private class RecognizingScanner(private val content: String) : QrScannerCapability {
        var scans = 0
        override suspend fun scan(prompt: String): QrScanResult {
            scans++
            return QrScanResult.Recognized(content)
        }
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
            matchNumber: Int = 1,
        ) = GameSessionSnapshot(
            sessionId = "session-1",
            joinCode = "ABC123",
            gameType = "xo",
            status = status,
            matchNumber = matchNumber,
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
