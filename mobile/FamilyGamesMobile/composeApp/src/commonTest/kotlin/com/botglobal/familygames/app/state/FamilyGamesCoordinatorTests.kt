package com.botglobal.familygames.app.state

import com.botglobal.familygames.app.data.FamilyGamesGateway
import com.botglobal.familygames.app.data.GameSessionSnapshot
import com.botglobal.familygames.app.data.MoveRequest
import com.botglobal.familygames.app.data.PlayerSnapshot
import com.botglobal.familygames.app.data.RegistrationRequest
import com.botglobal.familygames.app.data.RulesetSnapshot
import com.botglobal.familygames.app.realtime.GameRealtimeClient
import com.botglobal.familygames.app.realtime.GameRealtimeEvent
import com.botglobal.familygames.app.realtime.RealtimeConnectSource
import com.botglobal.mobile.platform.device.HapticEvent
import com.botglobal.mobile.platform.device.SemanticHaptics
import com.botglobal.mobile.platform.identity.ApplicationIdentity
import com.botglobal.mobile.platform.identity.IdentityKind
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.realtime.RealtimeConnectionState
import com.botglobal.mobile.platform.realtime.NetworkAvailabilitySnapshot
import com.botglobal.mobile.platform.realtime.NetworkAvailabilityState
import com.botglobal.mobile.platform.invitations.GameInvitation
import com.botglobal.mobile.platform.invitations.QrScanResult
import com.botglobal.mobile.platform.invitations.QrScannerCapability
import com.botglobal.mobile.platform.device.PermissionController
import com.botglobal.mobile.platform.device.PermissionKind
import com.botglobal.mobile.platform.device.PermissionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import com.botglobal.familygames.app.data.ApiException
import com.botglobal.mobile.platform.update.AppVersionPolicy
import com.botglobal.mobile.platform.realtime.NetworkAvailability

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
    fun reconnect_completion_recovers_without_foreground_event() = runTest {
        val gateway = FakeGateway(
            restored = mobileSession,
            active = game(version = 3, status = "started"),
            rejoinResult = game(version = 8, status = "started", activePlayer = membershipId),
        )
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(gateway, realtime, SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()

        realtime.setConnection(RealtimeConnectionState.Reconnecting)
        runCurrent()
        realtime.setConnection(RealtimeConnectionState.Connected)
        advanceUntilIdle()

        assertEquals(1, gateway.rejoinCalls)
        assertEquals(8, coordinator.state.value.game?.version)
        assertEquals(RealtimeConnectionState.Connected, coordinator.state.value.connection)
        assertEquals(true, coordinator.state.value.recoveredFromInterruption)
        assertNull(coordinator.state.value.errorCode)
        coordinator.dispose()
    }

    @Test
    fun network_restoration_resumes_the_canonical_owner_without_foreground_event() = runTest {
        val gateway = FakeGateway(
            restored = mobileSession,
            active = game(version = 3, status = "started"),
            rejoinResult = game(version = 8, status = "started", activePlayer = membershipId),
        )
        val realtime = FakeRealtime(connectOnStart = true)
        val network = FakeNetworkAvailability()
        val coordinator = FamilyGamesCoordinator(
            gateway,
            realtime,
            SilentHaptics,
            this,
            networkAvailability = network,
        )
        coordinator.startup()
        advanceUntilIdle()

        realtime.setConnection(RealtimeConnectionState.Reconnecting)
        realtime.setConnection(RealtimeConnectionState.Failed)
        runCurrent()
        network.setAvailable(false)
        runCurrent()
        network.setAvailable(true)
        advanceUntilIdle()

        assertEquals(1, realtime.startCalls)
        assertEquals(1, gateway.rejoinCalls)
        assertEquals(RealtimeConnectionState.Connected, coordinator.state.value.connection)
        assertEquals(SessionRecoveryState.Recovered, coordinator.state.value.recovery)
        assertNull(coordinator.state.value.errorCode)
        coordinator.dispose()
    }

    @Test
    fun network_unavailable_promptly_pauses_moves_without_starting_another_transport() = runTest {
        val gateway = FakeGateway(
            restored = mobileSession,
            active = game(version = 3, status = "started", activePlayer = membershipId),
        )
        val realtime = FakeRealtime()
        val network = FakeNetworkAvailability()
        val coordinator = FamilyGamesCoordinator(
            gateway,
            realtime,
            SilentHaptics,
            this,
            networkAvailability = network,
        )
        coordinator.startup()
        advanceUntilIdle()

        network.setAvailable(false)
        advanceUntilIdle()
        coordinator.play(0, 0)
        advanceUntilIdle()

        assertEquals(RealtimeConnectionState.Reconnecting, coordinator.state.value.connection)
        assertEquals(SessionRecoveryState.Interrupted, coordinator.state.value.recovery)
        assertEquals(1, realtime.startCalls)
        assertNull(gateway.lastMove)
        coordinator.dispose()
    }

    @Test
    fun duplicate_connectivity_callbacks_coalesce_and_success_clears_network_recovery_state() = runTest {
        val gateway = FakeGateway(
            restored = mobileSession,
            active = game(version = 3, status = "started"),
            rejoinResult = game(version = 9, status = "started", activePlayer = membershipId),
        )
        val realtime = FakeRealtime()
        val network = FakeNetworkAvailability()
        val coordinator = FamilyGamesCoordinator(
            gateway,
            realtime,
            SilentHaptics,
            this,
            networkAvailability = network,
        )
        coordinator.startup()
        advanceUntilIdle()

        network.setAvailable(false)
        network.setAvailable(false)
        advanceUntilIdle()
        network.setAvailable(true)
        network.setAvailable(true)
        advanceUntilIdle()

        assertEquals(1, realtime.startCalls)
        assertEquals(1, realtime.effectiveNetworkLosses)
        assertEquals(1, realtime.effectiveNetworkReturns)
        assertEquals(1, gateway.rejoinCalls)
        assertEquals(RealtimeConnectionState.Connected, coordinator.state.value.connection)
        assertEquals(SessionRecoveryState.Recovered, coordinator.state.value.recovery)
        assertNull(coordinator.state.value.errorCode)
        coordinator.dispose()
    }

    @Test
    fun successful_recovery_clears_an_older_unrecoverable_error() = runTest {
        val gateway = FakeGateway(
            restored = mobileSession,
            active = game(version = 3, status = "started"),
            rejoinBehavior = { call ->
                if (call == 1) throw ApiException("session_not_found", 404, "Gone")
                game(version = 9, status = "started", activePlayer = membershipId)
            },
        )
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(gateway, realtime, SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()

        realtime.setConnection(RealtimeConnectionState.Reconnecting)
        runCurrent()
        realtime.setConnection(RealtimeConnectionState.Connected)
        advanceUntilIdle()
        assertEquals("recovery_failed", coordinator.state.value.errorCode)

        realtime.setConnection(RealtimeConnectionState.Reconnecting)
        runCurrent()
        realtime.setConnection(RealtimeConnectionState.Connected)
        advanceUntilIdle()

        assertEquals(2, gateway.rejoinCalls)
        assertEquals(9, coordinator.state.value.game?.version)
        assertEquals(RealtimeConnectionState.Connected, coordinator.state.value.connection)
        assertNull(coordinator.state.value.errorCode)
        coordinator.dispose()
    }

    @Test
    fun stale_failed_attempt_cannot_override_newer_success() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val gateway = FakeGateway(
            restored = mobileSession,
            active = game(version = 3, status = "started"),
            rejoinBehavior = { call ->
                if (call == 1) {
                    firstStarted.complete(Unit)
                    withContext(NonCancellable) { releaseFirst.await() }
                    throw ApiException("session_not_found", 404, "Late failure")
                }
                game(version = 10, status = "started", activePlayer = membershipId)
            },
        )
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(gateway, realtime, SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()

        realtime.setConnection(RealtimeConnectionState.Reconnecting)
        runCurrent()
        realtime.setConnection(RealtimeConnectionState.Connected)
        runCurrent()
        firstStarted.await()

        realtime.setConnection(RealtimeConnectionState.Reconnecting)
        runCurrent()
        realtime.setConnection(RealtimeConnectionState.Connected)
        runCurrent()
        assertEquals(2, gateway.rejoinCalls)
        assertEquals(10, coordinator.state.value.game?.version)

        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(10, coordinator.state.value.game?.version)
        assertEquals(RealtimeConnectionState.Connected, coordinator.state.value.connection)
        assertNull(coordinator.state.value.errorCode)
        coordinator.dispose()
    }

    @Test
    fun duplicate_reconnect_callbacks_coalesce_to_one_recovery() = runTest {
        val releaseRecovery = CompletableDeferred<Unit>()
        val gateway = FakeGateway(
            restored = mobileSession,
            active = game(version = 3, status = "started"),
            rejoinBehavior = {
                releaseRecovery.await()
                game(version = 6, status = "started")
            },
        )
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(gateway, realtime, SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()

        realtime.setConnection(RealtimeConnectionState.Reconnecting)
        realtime.setConnection(RealtimeConnectionState.Reconnecting)
        runCurrent()
        realtime.setConnection(RealtimeConnectionState.Connected)
        realtime.setConnection(RealtimeConnectionState.Connected)
        runCurrent()

        assertEquals(1, gateway.rejoinCalls)
        coordinator.resumeAfterForeground()
        runCurrent()
        assertEquals(1, gateway.rejoinCalls)

        releaseRecovery.complete(Unit)
        advanceUntilIdle()
        assertEquals(6, coordinator.state.value.game?.version)
        coordinator.dispose()
    }

    @Test
    fun recovery_force_replaces_a_stale_local_board_with_authoritative_state() = runTest {
        val stale = game(version = 99, status = "started").copy(
            board = listOf("x", "", "", "", "", "", "", "", ""),
        )
        val authoritative = game(version = 7, status = "started", activePlayer = membershipId).copy(
            board = listOf("", "", "", "", "o", "", "", "", ""),
        )
        val gateway = FakeGateway(restored = mobileSession, active = stale, rejoinResult = authoritative)
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(gateway, realtime, SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()

        realtime.setConnection(RealtimeConnectionState.Reconnecting)
        runCurrent()
        realtime.setConnection(RealtimeConnectionState.Connected)
        advanceUntilIdle()

        assertEquals(7, coordinator.state.value.game?.version)
        assertEquals(authoritative.board, coordinator.state.value.game?.board)
        assertNull(coordinator.state.value.errorCode)
        coordinator.dispose()
    }

    @Test
    fun recovery_publishes_authoritative_completion_while_client_was_offline() = runTest {
        val completed = game(version = 12, status = "completed").copy(
            board = listOf("x", "x", "x", "o", "o", "", "", "", ""),
            matchStatus = "won",
            winnerMembershipId = membershipId,
            activePlayerMembershipId = null,
        )
        val gateway = FakeGateway(
            restored = mobileSession,
            active = game(version = 4, status = "started"),
            rejoinResult = completed,
        )
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(gateway, realtime, SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()

        realtime.setConnection(RealtimeConnectionState.Reconnecting)
        runCurrent()
        realtime.setConnection(RealtimeConnectionState.Connected)
        advanceUntilIdle()

        assertEquals(AppScreen.Result, coordinator.state.value.screen)
        assertEquals("completed", coordinator.state.value.game?.status)
        assertEquals(completed.board, coordinator.state.value.game?.board)
        assertEquals(membershipId, coordinator.state.value.game?.winnerMembershipId)
        assertNull(coordinator.state.value.errorCode)
        coordinator.dispose()
    }

    @Test
    fun opponent_disconnect_event_publishes_generic_disconnected_state() = runTest {
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(
            FakeGateway(restored = mobileSession, active = game(status = "started", revision = 10)),
            realtime,
            SilentHaptics,
            this,
        )
        coordinator.startup()
        advanceUntilIdle()

        realtime.emit(game(status = "started", opponentConnected = false, revision = 11))
        advanceUntilIdle()

        assertEquals(OpponentConnectionState.Disconnected, coordinator.state.value.opponentConnection)
        coordinator.dispose()
    }

    @Test
    fun opponent_rejoin_event_clears_disconnected_state() = runTest {
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(
            FakeGateway(
                restored = mobileSession,
                active = game(status = "started", opponentConnected = false, revision = 20),
            ),
            realtime,
            SilentHaptics,
            this,
        )
        coordinator.startup()
        advanceUntilIdle()

        realtime.emit(game(status = "started", opponentConnected = true, revision = 21))
        advanceUntilIdle()

        assertEquals(OpponentConnectionState.Connected, coordinator.state.value.opponentConnection)
        coordinator.dispose()
    }

    @Test
    fun fast_disconnect_reconnect_does_not_leave_opponent_stuck_disconnected() = runTest {
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(
            FakeGateway(restored = mobileSession, active = game(status = "started", revision = 30)),
            realtime,
            SilentHaptics,
            this,
        )
        coordinator.startup()
        advanceUntilIdle()

        realtime.emit(game(status = "started", opponentConnected = false, revision = 31))
        realtime.emit(game(status = "started", opponentConnected = true, revision = 32))
        advanceUntilIdle()

        assertEquals(OpponentConnectionState.Connected, coordinator.state.value.opponentConnection)
        coordinator.dispose()
    }

    @Test
    fun stale_disconnect_event_cannot_override_newer_reconnected_presence() = runTest {
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(
            FakeGateway(restored = mobileSession, active = game(status = "started", revision = 40)),
            realtime,
            SilentHaptics,
            this,
        )
        coordinator.startup()
        advanceUntilIdle()

        realtime.emit(game(status = "started", opponentConnected = true, revision = 42))
        realtime.emit(game(status = "started", opponentConnected = false, revision = 41))
        advanceUntilIdle()

        assertEquals(42, coordinator.state.value.game?.revision)
        assertEquals(OpponentConnectionState.Connected, coordinator.state.value.opponentConnection)
        coordinator.dispose()
    }

    @Test
    fun authoritative_rejoin_refresh_replaces_opponent_presence() = runTest {
        val gateway = FakeGateway(
            restored = mobileSession,
            active = game(status = "started", opponentConnected = false, revision = 50),
            rejoinResult = game(status = "started", opponentConnected = true, revision = 51),
        )
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(gateway, realtime, SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()

        realtime.setConnection(RealtimeConnectionState.Reconnecting)
        runCurrent()
        realtime.setConnection(RealtimeConnectionState.Connected)
        advanceUntilIdle()

        assertEquals(OpponentConnectionState.Connected, coordinator.state.value.opponentConnection)
        assertEquals(51, coordinator.state.value.game?.revision)
        coordinator.dispose()
    }

    @Test
    fun presence_projection_is_generic_and_independent_of_xo_state() {
        val futureGame = game(gameType = "future-family-game", opponentConnected = false)

        assertEquals(
            OpponentConnectionState.Disconnected,
            futureGame.opponentConnectionState(membershipId),
        )
    }

    @Test
    fun foreground_resume_uses_the_same_authoritative_recovery_owner() = runTest {
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

        assertEquals(0, realtime.rejoinCalls)
        assertEquals(1, gateway.rejoinCalls)
        assertEquals(7, coordinator.state.value.game?.version)
        assertEquals(SessionRecoveryState.Recovered, coordinator.state.value.recovery)
        assertEquals(true, coordinator.state.value.recoveredFromInterruption)
        coordinator.dispose()
    }

    @Test
    fun duplicate_foreground_events_while_connected_do_not_restart_transport() = runTest {
        val gateway = FakeGateway(restored = mobileSession, active = game(status = "started"))
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(gateway, realtime, SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()

        coordinator.resumeAfterForeground()
        coordinator.resumeAfterForeground()
        advanceUntilIdle()

        assertEquals(1, realtime.startCalls)
        assertEquals(RealtimeConnectionState.Connected, realtime.connectionState.value)
        coordinator.dispose()
    }

    @Test
    fun foreground_while_automatic_reconnect_is_active_does_not_restart_transport() = runTest {
        val gateway = FakeGateway(restored = mobileSession, active = game(status = "started"))
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(gateway, realtime, SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()

        realtime.setConnection(RealtimeConnectionState.Reconnecting)
        runCurrent()
        coordinator.resumeAfterForeground()
        advanceUntilIdle()

        assertEquals(1, realtime.startCalls)
        assertEquals(RealtimeConnectionState.Reconnecting, realtime.connectionState.value)
        coordinator.dispose()
    }

    @Test
    fun stable_observer_presence_changes_only_from_server_events() = runTest {
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(
            FakeGateway(restored = mobileSession, active = game(status = "started", revision = 60)),
            realtime,
            SilentHaptics,
            this,
        )
        coordinator.startup()
        advanceUntilIdle()
        val startsBeforePresence = realtime.startCalls

        realtime.emit(game(status = "started", opponentConnected = false, revision = 61))
        advanceUntilIdle()
        assertEquals(OpponentConnectionState.Disconnected, coordinator.state.value.opponentConnection)
        assertEquals(RealtimeConnectionState.Connected, realtime.connectionState.value)

        realtime.emit(game(status = "started", opponentConnected = true, revision = 62))
        advanceUntilIdle()

        assertEquals(OpponentConnectionState.Connected, coordinator.state.value.opponentConnection)
        assertEquals(RealtimeConnectionState.Connected, realtime.connectionState.value)
        assertEquals(startsBeforePresence, realtime.startCalls)
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
        private val rejoinBehavior: (suspend (Int) -> GameSessionSnapshot)? = null,
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
            rejoinBehavior?.let { return it(rejoinCalls) }
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

    private class FakeRealtime(
        private val connectOnStart: Boolean = false,
    ) : GameRealtimeClient {
        private val mutableEvents = MutableSharedFlow<GameRealtimeEvent>(extraBufferCapacity = 4)
        private val mutableConnection = MutableStateFlow(RealtimeConnectionState.Connected)
        override val connectionState: StateFlow<RealtimeConnectionState> = mutableConnection
        override val events: Flow<GameRealtimeEvent> = mutableEvents
        var startedSession: String? = null
        var startCalls: Int = 0
        var rejoinCalls: Int = 0
        var effectiveNetworkLosses: Int = 0
        var effectiveNetworkReturns: Int = 0
        private var networkObserverGeneration = Long.MIN_VALUE
        private var networkRevision = Long.MIN_VALUE
        private var networkState = NetworkAvailabilityState.Unknown
        override suspend fun start(
            sessionId: String,
            source: RealtimeConnectSource,
            accessToken: suspend () -> String?,
        ) {
            startCalls++
            startedSession = sessionId
            if (connectOnStart) {
                mutableConnection.value = RealtimeConnectionState.Connecting
                mutableConnection.value = RealtimeConnectionState.Connected
            }
        }
        override suspend fun stop() = Unit
        override suspend fun rejoin() { rejoinCalls++ }
        override suspend fun onNetworkAvailabilityChanged(snapshot: NetworkAvailabilitySnapshot) {
            val current = snapshot.observerGeneration > networkObserverGeneration ||
                snapshot.observerGeneration == networkObserverGeneration && snapshot.revision > networkRevision
            if (!current) return
            val previous = networkState
            networkObserverGeneration = snapshot.observerGeneration
            networkRevision = snapshot.revision
            networkState = snapshot.state
            if (previous == snapshot.state || startedSession == null) return
            when (snapshot.state) {
                NetworkAvailabilityState.Unavailable -> {
                    effectiveNetworkLosses++
                    mutableConnection.value = RealtimeConnectionState.Reconnecting
                }
                NetworkAvailabilityState.Available -> {
                    effectiveNetworkReturns++
                    if (mutableConnection.value != RealtimeConnectionState.Connected) {
                        mutableConnection.value = RealtimeConnectionState.Connected
                    }
                }
                NetworkAvailabilityState.Unknown -> Unit
            }
        }
        fun emit(snapshot: GameSessionSnapshot) { mutableEvents.tryEmit(GameRealtimeEvent("GameStateUpdated", snapshot)) }
        fun setConnection(state: RealtimeConnectionState) { mutableConnection.value = state }
    }

    private class FakeNetworkAvailability : NetworkAvailability {
        private val mutableChanges = MutableSharedFlow<NetworkAvailabilitySnapshot>(extraBufferCapacity = 4)
        private var revision = 0L
        override val changes: Flow<NetworkAvailabilitySnapshot> = mutableChanges
        fun setAvailable(available: Boolean) {
            mutableChanges.tryEmit(
                NetworkAvailabilitySnapshot(
                    state = if (available) {
                        NetworkAvailabilityState.Available
                    } else {
                        NetworkAvailabilityState.Unavailable
                    },
                    observerGeneration = 1,
                    revision = ++revision,
                ),
            )
        }
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
            gameType: String = "xo",
            opponentConnected: Boolean = true,
            revision: Long = 0,
        ) = GameSessionSnapshot(
            sessionId = "session-1",
            joinCode = "ABC123",
            gameType = gameType,
            status = status,
            matchNumber = matchNumber,
            ruleset = RulesetSnapshot("classic-3x3", 3, 3, 2, null, true, false),
            players = listOf(
                PlayerSnapshot(membershipId, "Player", 0, "x", true, true),
                PlayerSnapshot("member-2", "Opponent", 1, "o", true, opponentConnected),
            ),
            board = List(9) { "" },
            version = version,
            activePlayerMembershipId = activePlayer,
            matchStatus = "inprogress",
            lastActivityAtUtc = "2099-01-01T00:00:00Z",
            revision = revision,
        )
    }
}
