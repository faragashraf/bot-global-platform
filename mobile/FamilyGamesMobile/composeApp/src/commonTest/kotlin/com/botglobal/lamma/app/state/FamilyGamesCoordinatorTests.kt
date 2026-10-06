package com.botglobal.lamma.app.state

import com.botglobal.lamma.app.data.AccountDeletionAcceptance
import com.botglobal.lamma.app.data.AutobusFinishRoundRequest
import com.botglobal.lamma.app.data.AutobusAnswerSnapshot
import com.botglobal.lamma.app.data.AutobusCategorySnapshot
import com.botglobal.lamma.app.data.AutobusRevealRequest
import com.botglobal.lamma.app.data.AutobusSnapshot
import com.botglobal.lamma.app.data.AutobusSubmitAnswersRequest
import com.botglobal.lamma.app.data.AutobusVoteRequest

import com.botglobal.lamma.app.data.FamilyGamesGateway
import com.botglobal.lamma.app.data.GameSessionSnapshot
import com.botglobal.lamma.app.data.MoveRequest
import com.botglobal.lamma.app.data.PlayerSnapshot
import com.botglobal.lamma.app.data.RegistrationRequest
import com.botglobal.lamma.app.data.RulesetSnapshot
import com.botglobal.lamma.app.realtime.GameRealtimeClient
import com.botglobal.lamma.app.realtime.GameRealtimeEvent
import com.botglobal.lamma.app.realtime.RealtimeConnectSource
import com.botglobal.mobile.platform.device.HapticEvent
import com.botglobal.mobile.platform.device.SemanticHaptics
import com.botglobal.mobile.platform.identity.ApplicationIdentity
import com.botglobal.mobile.platform.identity.FederatedCredential
import com.botglobal.mobile.platform.identity.FederatedCredentialProvider
import com.botglobal.mobile.platform.identity.FederatedCredentialResult
import com.botglobal.mobile.platform.identity.FederatedCredentialType
import com.botglobal.mobile.platform.identity.FederatedIdentityProvider
import com.botglobal.mobile.platform.identity.IdentityKind
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.preferences.InMemoryPreferenceStore
import com.botglobal.mobile.platform.reviews.ReviewCoordinator
import com.botglobal.mobile.platform.reviews.ReviewLaunchOutcome
import com.botglobal.mobile.platform.reviews.ReviewPolicy
import com.botglobal.mobile.platform.reviews.ReviewPromptLauncher
import com.botglobal.mobile.platform.reviews.RatingInvitationCoordinator
import com.botglobal.mobile.platform.reviews.RatingInvitationPolicy
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import kotlin.test.assertTrue
import com.botglobal.lamma.app.data.ApiException
import com.botglobal.mobile.platform.update.AppVersionPolicy
import com.botglobal.mobile.platform.realtime.NetworkAvailability
import com.botglobal.mobile.platform.voice.IceServer
import com.botglobal.mobile.platform.voice.VoiceConsentResult
import com.botglobal.mobile.platform.voice.VoiceConsentSignal
import com.botglobal.mobile.platform.voice.VoiceIceConfiguration
import com.botglobal.mobile.platform.voice.VoiceJoinResult
import com.botglobal.mobile.platform.voice.VoiceMediaPeer
import com.botglobal.mobile.platform.voice.VoiceMediaPeerFactory
import com.botglobal.mobile.platform.voice.VoiceMediaPeerListener

@OptIn(ExperimentalCoroutinesApi::class)
class FamilyGamesCoordinatorTests {
    @Test
    fun expired_server_session_returns_to_welcome_with_a_clear_message() = runTest {
        val gateway = FakeGateway(
            restored = mobileSession,
            createError = ApiException("session_expired", 401, "Expired"),
        )
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(gateway, realtime, SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()

        coordinator.createClassicGame()
        advanceUntilIdle()

        assertEquals(AppScreen.Welcome, coordinator.state.value.screen)
        assertNull(coordinator.state.value.mobileSession)
        assertEquals("session_expired", coordinator.state.value.errorCode)
        assertEquals(1, gateway.clearCalls)
        assertEquals(1, realtime.stopCalls)
        coordinator.dispose()
    }

    @Test
    fun microphone_permission_and_media_are_not_started_before_remote_acceptance() = runTest {
        val realtime = FakeRealtime()
        val permissions = RecordingMicrophonePermission()
        val media = RecordingVoiceMediaFactory()
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = FamilyGamesCoordinator(
            FakeGateway(restored = mobileSession, active = game(status = "started", voiceEnabled = true)),
            realtime,
            SilentHaptics,
            coordinatorScope,
            permissions = permissions,
            voiceMediaFactory = media,
        )
        coordinator.startup()
        advanceUntilIdle()

        coordinator.requestVoiceChat()
        advanceUntilIdle()
        assertEquals(1, realtime.voiceRequests)
        assertEquals(0, permissions.requests)
        assertEquals(0, media.creations)

        realtime.emitConsent(VoiceConsentSignal.Accepted(
            "session-1", 1, "request-1", membershipId, "connection-a",
            "member-2", "connection-b", "2099-01-01T00:00:00Z",
        ))
        realtime.emitConsent(VoiceConsentSignal.Accepted(
            "session-1", 1, "request-1", membershipId, "connection-a",
            "member-2", "connection-b", "2099-01-01T00:00:00Z",
        ))
        runCurrent()
        assertEquals(0, permissions.requests)
        assertEquals(0, media.creations)

        coordinator.confirmMicrophoneAndJoinVoice()
        advanceUntilIdle()
        coordinator.confirmMicrophoneAndJoinVoice()
        advanceUntilIdle()
        assertEquals(1, permissions.requests)
        assertEquals(1, media.creations)

        realtime.emitConsent(VoiceConsentSignal.Ended(
            "session-1", 1, "request-1", membershipId, "connection-a",
            "member-2", "connection-b", "2099-01-01T00:00:00Z",
        ))
        advanceUntilIdle()
        assertEquals(1, media.closes)
        coordinator.dispose()
        coordinatorScope.cancel()
    }

    @Test
    fun explicit_english_selection_is_saved() = runTest {
        val preferences = FakeLanguagePreferences()
        val coordinator = FamilyGamesCoordinator(
            FakeGateway(),
            FakeRealtime(),
            SilentHaptics,
            this,
            languagePreferences = preferences,
        )

        coordinator.toggleLanguage()

        assertEquals(AppLanguage.English, coordinator.state.value.language)
        assertEquals(AppLanguage.English, preferences.stored)
        coordinator.dispose()
    }

    @Test
    fun explicit_arabic_selection_is_saved() = runTest {
        val preferences = FakeLanguagePreferences(AppLanguage.English)
        val coordinator = FamilyGamesCoordinator(
            FakeGateway(),
            FakeRealtime(),
            SilentHaptics,
            this,
            languagePreferences = preferences,
        )

        coordinator.toggleLanguage()

        assertEquals(AppLanguage.Arabic, coordinator.state.value.language)
        assertEquals(AppLanguage.Arabic, preferences.stored)
        coordinator.dispose()
    }

    @Test
    fun startup_restores_english_before_session_work() = runTest {
        val coordinator = FamilyGamesCoordinator(
            FakeGateway(),
            FakeRealtime(),
            SilentHaptics,
            this,
            languagePreferences = FakeLanguagePreferences(AppLanguage.English),
        )

        assertEquals(AppLanguage.English, coordinator.state.value.language)
        coordinator.startup()
        advanceUntilIdle()

        assertEquals(AppLanguage.English, coordinator.state.value.language)
        coordinator.dispose()
    }

    @Test
    fun startup_restores_arabic_before_session_work() = runTest {
        val coordinator = FamilyGamesCoordinator(
            FakeGateway(),
            FakeRealtime(),
            SilentHaptics,
            this,
            languagePreferences = FakeLanguagePreferences(AppLanguage.Arabic),
        )

        assertEquals(AppLanguage.Arabic, coordinator.state.value.language)
        coordinator.startup()
        advanceUntilIdle()

        assertEquals(AppLanguage.Arabic, coordinator.state.value.language)
        coordinator.dispose()
    }

    @Test
    fun process_recreation_restores_the_last_explicit_selection() = runTest {
        val preferences = FakeLanguagePreferences()
        val first = FamilyGamesCoordinator(
            FakeGateway(),
            FakeRealtime(),
            SilentHaptics,
            this,
            languagePreferences = preferences,
        )
        first.toggleLanguage()
        first.dispose()

        val recreated = FamilyGamesCoordinator(
            FakeGateway(),
            FakeRealtime(),
            SilentHaptics,
            this,
            languagePreferences = preferences,
        )

        assertEquals(AppLanguage.English, recreated.state.value.language)
        recreated.dispose()
    }

    @Test
    fun guest_creation_does_not_reset_restored_language() = runTest {
        val coordinator = FamilyGamesCoordinator(
            FakeGateway(),
            FakeRealtime(),
            SilentHaptics,
            this,
            languagePreferences = FakeLanguagePreferences(AppLanguage.English),
        )
        coordinator.startup()
        advanceUntilIdle()

        coordinator.continueAsGuest("Player")
        advanceUntilIdle()

        assertEquals(AppLanguage.English, coordinator.state.value.language)
        coordinator.dispose()
    }

    @Test
    fun restored_identity_and_game_do_not_reset_language() = runTest {
        val coordinator = FamilyGamesCoordinator(
            FakeGateway(restored = mobileSession, active = game(status = "started")),
            FakeRealtime(),
            SilentHaptics,
            this,
            languagePreferences = FakeLanguagePreferences(AppLanguage.English),
        )

        coordinator.startup()
        advanceUntilIdle()

        assertEquals(AppLanguage.English, coordinator.state.value.language)
        assertEquals(AppScreen.Gameplay, coordinator.state.value.screen)
        coordinator.dispose()
    }

    @Test
    fun cold_start_deep_link_does_not_reset_language() = runTest {
        val coordinator = FamilyGamesCoordinator(
            FakeGateway(restored = mobileSession, resolvedInvitation = game()),
            FakeRealtime(),
            SilentHaptics,
            this,
            languagePreferences = FakeLanguagePreferences(AppLanguage.English),
        )

        coordinator.handleInvitationLink("familygames://invite/AbCdEf0123456789_opaque-token")
        coordinator.startup()
        advanceUntilIdle()

        assertEquals(AppLanguage.English, coordinator.state.value.language)
        assertEquals(AppScreen.Lobby, coordinator.state.value.screen)
        coordinator.dispose()
    }

    @Test
    fun invitation_resolution_does_not_reset_language() = runTest {
        val coordinator = FamilyGamesCoordinator(
            FakeGateway(restored = mobileSession, resolvedInvitation = game()),
            FakeRealtime(),
            SilentHaptics,
            this,
            languagePreferences = FakeLanguagePreferences(AppLanguage.English),
        )
        coordinator.startup()
        advanceUntilIdle()

        coordinator.handleInvitationLink("familygames://invite/AbCdEf0123456789_opaque-token")
        advanceUntilIdle()

        assertEquals(AppLanguage.English, coordinator.state.value.language)
        assertEquals(AppScreen.Lobby, coordinator.state.value.screen)
        coordinator.dispose()
    }

    @Test
    fun realtime_recovery_does_not_reset_language() = runTest {
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(
            FakeGateway(
                restored = mobileSession,
                active = game(version = 3, status = "started"),
                rejoinResult = game(version = 4, status = "started"),
            ),
            realtime,
            SilentHaptics,
            this,
            languagePreferences = FakeLanguagePreferences(AppLanguage.English),
        )
        coordinator.startup()
        advanceUntilIdle()

        realtime.setConnection(RealtimeConnectionState.Reconnecting)
        runCurrent()
        realtime.setConnection(RealtimeConnectionState.Connected)
        advanceUntilIdle()

        assertEquals(AppLanguage.English, coordinator.state.value.language)
        assertEquals(RealtimeConnectionState.Connected, coordinator.state.value.connection)
        coordinator.dispose()
    }

    @Test
    fun first_time_user_keeps_the_approved_arabic_default() = runTest {
        val coordinator = FamilyGamesCoordinator(
            FakeGateway(),
            FakeRealtime(),
            SilentHaptics,
            this,
            languagePreferences = FakeLanguagePreferences(),
        )

        assertEquals(AppLanguage.Arabic, coordinator.state.value.language)
        coordinator.startup()
        advanceUntilIdle()

        assertEquals(AppLanguage.Arabic, coordinator.state.value.language)
        coordinator.dispose()
    }

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
    fun completed_game_is_restored_from_recent_session_after_app_restart() = runTest {
        val completed = game(version = 18, status = "completed", gameType = "autobus")
        val preferences = FakeRecentGameSessionPreferences(completed.sessionId)
        val gateway = FakeGateway(
            restored = mobileSession,
            rejoinResult = completed,
        )
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(
            gateway,
            realtime,
            SilentHaptics,
            this,
            recentGameSessionPreferences = preferences,
        )

        coordinator.startup()
        advanceUntilIdle()

        assertEquals(1, gateway.rejoinCalls)
        assertEquals(AppScreen.Result, coordinator.state.value.screen)
        assertEquals(completed, coordinator.state.value.game)
        assertEquals(completed.sessionId, realtime.startedSession)
        assertEquals(completed.sessionId, preferences.stored)
        coordinator.dispose()
    }

    @Test
    fun explicit_game_exit_clears_recent_session_and_does_not_reopen_result() = runTest {
        val completed = game(version = 18, status = "completed", gameType = "autobus")
        val preferences = FakeRecentGameSessionPreferences(completed.sessionId)
        val firstCoordinator = FamilyGamesCoordinator(
            FakeGateway(restored = mobileSession, rejoinResult = completed),
            FakeRealtime(),
            SilentHaptics,
            this,
            recentGameSessionPreferences = preferences,
        )
        firstCoordinator.startup()
        advanceUntilIdle()

        firstCoordinator.exitGame()
        advanceUntilIdle()

        assertEquals(AppScreen.Home, firstCoordinator.state.value.screen)
        assertNull(preferences.stored)
        firstCoordinator.dispose()

        val freshGateway = FakeGateway(restored = mobileSession)
        val freshCoordinator = FamilyGamesCoordinator(
            freshGateway,
            FakeRealtime(),
            SilentHaptics,
            this,
            recentGameSessionPreferences = preferences,
        )
        freshCoordinator.startup()
        advanceUntilIdle()

        assertEquals(0, freshGateway.rejoinCalls)
        assertEquals(AppScreen.Home, freshCoordinator.state.value.screen)
        assertNull(freshCoordinator.state.value.game)
        freshCoordinator.dispose()
    }

    @Test
    fun google_cancellation_returns_to_welcome_without_error_loop() = runTest {
        val coordinator = FamilyGamesCoordinator(
            FakeGateway(),
            FakeRealtime(),
            SilentHaptics,
            this,
            federatedCredentials = FakeCredentials(FederatedCredentialResult.Cancelled),
        )
        coordinator.startup()
        advanceUntilIdle()

        coordinator.signInWithGoogle()
        advanceUntilIdle()

        assertEquals(AppScreen.Welcome, coordinator.state.value.screen)
        assertEquals(null, coordinator.state.value.errorCode)
        assertEquals(null, coordinator.state.value.mobileSession)
        coordinator.dispose()
    }

    @Test
    fun google_sign_in_requires_profile_completion_and_persists_name_before_home() = runTest {
        val gateway = FakeGateway(
            federatedSession = mobileSession.copy(
                identity = mobileSession.identity.copy(kind = IdentityKind.Registered, displayName = "Google Name"),
            ),
        )
        val coordinator = FamilyGamesCoordinator(
            gateway,
            FakeRealtime(),
            SilentHaptics,
            this,
            federatedCredentials = FakeCredentials(),
        )
        coordinator.startup()
        advanceUntilIdle()

        coordinator.signInWithGoogle()
        advanceUntilIdle()
        assertEquals(AppScreen.ProfileCompletion, coordinator.state.value.screen)

        coordinator.updateProfileDraft("  Lamma Player  ")
        coordinator.completeProfile()
        advanceUntilIdle()

        assertEquals("Lamma Player", gateway.savedProfileName)
        assertEquals("Lamma Player", coordinator.state.value.mobileSession?.identity?.displayName)
        assertEquals(AppScreen.Home, coordinator.state.value.screen)
        coordinator.dispose()
    }

    @Test
    fun registered_home_profile_edit_saves_name_without_requiring_new_sign_in() = runTest {
        val gateway = FakeGateway(
            restored = mobileSession.copy(
                identity = mobileSession.identity.copy(kind = IdentityKind.Registered, displayName = "Before"),
            ),
            federatedSession = mobileSession.copy(
                identity = mobileSession.identity.copy(kind = IdentityKind.Registered, displayName = "Before"),
            ),
        )
        val coordinator = FamilyGamesCoordinator(
            gateway,
            FakeRealtime(),
            SilentHaptics,
            this,
        )
        coordinator.startup()
        advanceUntilIdle()

        coordinator.editProfile()
        coordinator.updateProfileDraft("After")
        coordinator.completeProfile()
        advanceUntilIdle()

        assertEquals("After", gateway.savedProfileName)
        assertEquals("After", coordinator.state.value.mobileSession?.identity?.displayName)
        assertEquals(AppScreen.Home, coordinator.state.value.screen)
        coordinator.dispose()
    }

    @Test
    fun completed_round_counts_once_for_review_even_after_duplicate_snapshot() = runTest {
        val launcher = CountingReviewLauncher()
        val reviews = ReviewCoordinator(
            InMemoryPreferenceStore(),
            "reviews",
            ReviewPolicy(firstUseAgeMillis = 0, minimumMeaningfulEvents = 1, minimumMeaningfulEventSpanMillis = 0),
            launcher,
        ) { 0L }
        val started = game(version = 4, status = "started", activePlayer = membershipId)
        val completed = started.copy(
            status = "completed",
            version = 5,
            matchStatus = "won",
            winnerMembershipId = membershipId,
        )
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(
            FakeGateway(restored = mobileSession, active = started, moveResult = completed),
            realtime,
            SilentHaptics,
            this,
            reviews = reviews,
        )
        coordinator.startup()
        advanceUntilIdle()

        coordinator.play(0, 0)
        realtime.emit(completed)
        advanceUntilIdle()

        assertEquals(1, launcher.launches)
        coordinator.dispose()
    }

    @Test
    fun completed_round_shows_rating_card_without_a_competing_native_prompt() = runTest {
        val store = InMemoryPreferenceStore()
        val launcher = CountingReviewLauncher()
        val reviews = ReviewCoordinator(
            store,
            "reviews",
            ReviewPolicy(firstUseAgeMillis = 0, minimumMeaningfulEvents = 1, minimumMeaningfulEventSpanMillis = 0),
            launcher,
        ) { 0L }
        val invitation = RatingInvitationCoordinator(
            store,
            "rating",
            RatingInvitationPolicy(minimumEvents = 1, minimumEventSpanMillis = 0, firstEventAgeMillis = 0),
        ) { 0L }
        val started = game(version = 4, status = "started", activePlayer = membershipId)
        val completed = started.copy(
            status = "completed",
            version = 5,
            matchStatus = "won",
            winnerMembershipId = membershipId,
        )
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(
            FakeGateway(restored = mobileSession, active = started, moveResult = completed),
            realtime,
            SilentHaptics,
            this,
            reviews = reviews,
            ratingInvitation = invitation,
        )
        coordinator.startup()
        advanceUntilIdle()

        coordinator.play(0, 0)
        realtime.emit(completed)
        advanceUntilIdle()

        assertTrue(invitation.visible.value)
        assertEquals(0, launcher.launches)
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
    fun multiplayer_presence_is_disconnected_when_any_other_player_is_offline() {
        val multiplayer = game(gameType = "autobus").copy(
            players = listOf(
                PlayerSnapshot(membershipId, "Player", 0, "1", true, true),
                PlayerSnapshot("member-2", "Connected", 1, "2", true, true),
                PlayerSnapshot("member-3", "Offline", 2, "3", true, false),
            ),
        )

        assertEquals(
            OpponentConnectionState.Disconnected,
            multiplayer.opponentConnectionState(membershipId),
        )
    }

    @Test
    fun autobus_submission_retries_once_after_authoritative_refresh() = runTest {
        val activeAutobus = autobusGame(version = 4)
        val recoveredAutobus = autobusGame(version = 7)
        val gateway = FakeGateway(
            restored = mobileSession,
            active = activeAutobus,
            rejoinResult = recoveredAutobus,
            autobusSubmitFailuresBeforeSuccess = 1,
        )
        val coordinator = FamilyGamesCoordinator(gateway, FakeRealtime(), SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()

        coordinator.updateAutobusAnswer("boy_name", "أحمد")
        coordinator.submitAutobusAnswers()
        advanceUntilIdle()

        assertEquals(2, gateway.autobusSubmitCalls)
        assertEquals(7, gateway.lastAutobusAnswers?.expectedVersion)
        assertNull(coordinator.state.value.errorCode)
        coordinator.dispose()
    }

    @Test
    fun autobus_timer_finish_is_queued_behind_an_in_flight_submission() = runTest {
        val releaseFirstSubmit = CompletableDeferred<Unit>()
        val activeAutobus = autobusGame(version = 4)
        val gateway = FakeGateway(
            restored = mobileSession,
            active = activeAutobus,
            autobusSubmitBehavior = { call, request ->
                if (call == 1) releaseFirstSubmit.await()
                autobusGame(request.expectedVersion + 1, "active")
            },
        )
        val coordinator = FamilyGamesCoordinator(gateway, FakeRealtime(), SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()

        coordinator.submitAutobusAnswers()
        runCurrent()
        coordinator.finishAutobusRound()
        releaseFirstSubmit.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, gateway.autobusSubmitCalls)
        assertEquals(1, gateway.autobusFinishCalls)
        coordinator.dispose()
    }

    @Test
    fun expired_grace_skips_closed_submission_and_locks_the_round() = runTest {
        val expiredGrace = autobusGame(
            version = 9,
            phase = "grace",
            graceEndsAtUtc = "2000-01-01T00:00:00Z",
        )
        val gateway = FakeGateway(restored = mobileSession, active = expiredGrace)
        val coordinator = FamilyGamesCoordinator(gateway, FakeRealtime(), SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()

        coordinator.finishAutobusRound()
        advanceUntilIdle()

        assertEquals(0, gateway.autobusSubmitCalls)
        assertEquals(1, gateway.autobusFinishCalls)
        coordinator.dispose()
    }

    @Test
    fun vote_expiry_is_queued_behind_an_in_flight_vote() = runTest {
        val releaseVote = CompletableDeferred<Unit>()
        val gateway = FakeGateway(
            restored = mobileSession,
            active = autobusGame(version = 4, phase = "reveal"),
            autobusVoteBehavior = { _, request ->
                releaseVote.await()
                autobusGame(request.expectedVersion + 1, phase = "reveal")
            },
        )
        val coordinator = FamilyGamesCoordinator(gateway, FakeRealtime(), SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()

        coordinator.voteAutobus("member-2", "boy_name", accept = true)
        runCurrent()
        coordinator.revealAutobus()
        releaseVote.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, gateway.autobusVoteCalls)
        assertEquals(1, gateway.autobusRevealCalls)
        coordinator.dispose()
    }

    @Test
    fun queued_vote_expiry_is_discarded_after_the_category_advances() = runTest {
        val releaseVote = CompletableDeferred<Unit>()
        val gateway = FakeGateway(
            restored = mobileSession,
            active = autobusGame(
                version = 4,
                phase = "reveal",
                revealCategoryKey = "boy_name",
                voteDeadlineAtUtc = "2099-01-01T00:01:00Z",
            ),
            autobusVoteBehavior = { _, request ->
                releaseVote.await()
                autobusGame(
                    version = request.expectedVersion + 1,
                    phase = "reveal",
                    revealCategoryKey = "girl_name",
                    voteDeadlineAtUtc = "2099-01-01T00:02:00Z",
                )
            },
        )
        val coordinator = FamilyGamesCoordinator(gateway, FakeRealtime(), SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()

        coordinator.voteAutobus("member-2", "boy_name", accept = true)
        runCurrent()
        coordinator.revealAutobus()
        releaseVote.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, gateway.autobusVoteCalls)
        assertEquals(0, gateway.autobusRevealCalls)
        coordinator.dispose()
    }

    @Test
    fun expired_vote_retries_after_authoritative_refresh_in_the_same_category() = runTest {
        val reveal = autobusGame(
            version = 4,
            phase = "reveal",
            revealCategoryKey = "boy_name",
            voteDeadlineAtUtc = "2000-01-01T00:00:00Z",
        )
        val gateway = FakeGateway(
            restored = mobileSession,
            active = reveal,
            rejoinResult = reveal.copy(version = 7),
            autobusRevealFailuresBeforeSuccess = 1,
        )
        val coordinator = FamilyGamesCoordinator(gateway, FakeRealtime(), SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()

        coordinator.revealAutobus()
        advanceUntilIdle()

        assertEquals(2, gateway.autobusRevealCalls)
        assertNull(coordinator.state.value.errorCode)
        coordinator.dispose()
    }

    @Test
    fun submission_recovery_does_not_carry_old_answers_into_a_new_round() = runTest {
        val gateway = FakeGateway(
            restored = mobileSession,
            active = autobusGame(version = 4, currentRound = 1),
            rejoinResult = autobusGame(version = 7, currentRound = 2),
            autobusSubmitFailuresBeforeSuccess = 1,
        )
        val coordinator = FamilyGamesCoordinator(gateway, FakeRealtime(), SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()

        coordinator.updateAutobusAnswer("boy_name", "أحمد")
        coordinator.submitAutobusAnswers()
        advanceUntilIdle()

        assertEquals(1, gateway.autobusSubmitCalls)
        assertEquals(2, coordinator.state.value.game?.autobus?.currentRound)
        coordinator.dispose()
    }

    @Test
    fun queued_round_expiry_is_discarded_after_the_round_advances() = runTest {
        val releaseSubmit = CompletableDeferred<Unit>()
        val gateway = FakeGateway(
            restored = mobileSession,
            active = autobusGame(version = 4, currentRound = 1),
            autobusSubmitBehavior = { _, request ->
                releaseSubmit.await()
                autobusGame(request.expectedVersion + 1, currentRound = 2)
            },
        )
        val coordinator = FamilyGamesCoordinator(gateway, FakeRealtime(), SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()

        coordinator.finishAutobusRound()
        runCurrent()
        coordinator.finishAutobusRound()
        releaseSubmit.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, gateway.autobusSubmitCalls)
        assertEquals(0, gateway.autobusFinishCalls)
        assertEquals(2, coordinator.state.value.game?.autobus?.currentRound)
        coordinator.dispose()
    }

    @Test
    fun newer_local_draft_survives_a_delayed_submit_response() = runTest {
        val releaseSubmit = CompletableDeferred<Unit>()
        val gateway = FakeGateway(
            restored = mobileSession,
            active = autobusGame(version = 4, ownAnswer = "أحمد"),
            autobusSubmitBehavior = { _, request ->
                releaseSubmit.await()
                autobusGame(request.expectedVersion + 1, ownAnswer = "أمل")
            },
        )
        val coordinator = FamilyGamesCoordinator(gateway, FakeRealtime(), SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()

        coordinator.updateAutobusAnswer("boy_name", "أمل")
        coordinator.submitAutobusAnswers()
        runCurrent()
        coordinator.updateAutobusAnswer("boy_name", "أيمن")
        releaseSubmit.complete(Unit)
        advanceUntilIdle()

        assertEquals("أيمن", coordinator.state.value.autobusDrafts["boy_name"])
        coordinator.dispose()
    }

    @Test
    fun newer_local_draft_survives_authoritative_recovery() = runTest {
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(
            FakeGateway(restored = mobileSession, active = autobusGame(version = 4, ownAnswer = "أحمد")),
            realtime,
            SilentHaptics,
            this,
        )
        coordinator.startup()
        advanceUntilIdle()

        coordinator.updateAutobusAnswer("boy_name", "أيمن")
        realtime.emit(autobusGame(version = 5, ownAnswer = "أحمد"))
        advanceUntilIdle()

        assertEquals("أيمن", coordinator.state.value.autobusDrafts["boy_name"])
        coordinator.dispose()
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

    @Test
    fun registered_deletion_requires_both_confirmations_and_acceptance_clears_session_once() = runTest {
        for (acceptance in AccountDeletionAcceptance.entries) {
            val completion = CompletableDeferred<AccountDeletionAcceptance>()
            val registered = mobileSession.copy(identity = mobileSession.identity.copy(kind = IdentityKind.Registered))
            val gateway = FakeGateway(restored = registered, deletionBehavior = { completion.await() })
            val realtime = FakeRealtime()
            val coordinator = FamilyGamesCoordinator(gateway, realtime, SilentHaptics, this)
            coordinator.startup()
            advanceUntilIdle()
            coordinator.deleteAccount()
            advanceUntilIdle()
            assertEquals(0, gateway.deletionCalls)
            coordinator.beginAccountDeletion()
            coordinator.deleteAccount()
            advanceUntilIdle()
            assertEquals(0, gateway.deletionCalls)
            coordinator.confirmAccountDeletionExplanation()
            coordinator.deleteAccount()
            coordinator.deleteAccount()
            runCurrent()
            assertEquals(1, gateway.deletionCalls)
            coordinator.cancelAccountDeletion()
            assertEquals(AccountDeletionConfirmation.Final, coordinator.state.value.accountDeletionConfirmation)
            assertEquals(0, gateway.clearCalls)
            completion.complete(acceptance)
            advanceUntilIdle()
            assertEquals(1, gateway.clearCalls)
            assertEquals(1, realtime.stopCalls)
            assertEquals(AppScreen.Welcome, coordinator.state.value.screen)
            assertEquals(null, coordinator.state.value.mobileSession)
            assertEquals(acceptance, coordinator.state.value.accountDeletionAcceptance)
            coordinator.dispose()
        }
    }

    @Test
    fun failed_deletion_preserves_session_and_can_retry() = runTest {
        val registered = mobileSession.copy(identity = mobileSession.identity.copy(kind = IdentityKind.Registered))
        var failing = true
        val gateway = FakeGateway(restored = registered, deletionBehavior = {
            if (failing) throw ApiException("request_failed", 503, "Retry")
            AccountDeletionAcceptance.Completed
        })
        val realtime = FakeRealtime()
        val coordinator = FamilyGamesCoordinator(gateway, realtime, SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()
        coordinator.beginAccountDeletion()
        coordinator.confirmAccountDeletionExplanation()
        coordinator.deleteAccount()
        advanceUntilIdle()
        assertEquals(registered, coordinator.state.value.mobileSession)
        assertEquals(true, coordinator.state.value.accountDeletionFailed)
        assertEquals(0, gateway.clearCalls)
        assertEquals(0, realtime.stopCalls)
        failing = false
        coordinator.deleteAccount()
        advanceUntilIdle()
        assertEquals(2, gateway.deletionCalls)
        assertEquals(AppScreen.Welcome, coordinator.state.value.screen)
        coordinator.dispose()
    }

    @Test
    fun guest_cannot_enter_or_submit_account_deletion() = runTest {
        val gateway = FakeGateway(restored = mobileSession)
        val coordinator = FamilyGamesCoordinator(gateway, FakeRealtime(), SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()
        coordinator.beginAccountDeletion()
        coordinator.confirmAccountDeletionExplanation()
        coordinator.deleteAccount()
        advanceUntilIdle()
        assertEquals(null, coordinator.state.value.accountDeletionConfirmation)
        assertEquals(0, gateway.deletionCalls)
        coordinator.dispose()
    }

    @Test
    fun accepted_deletion_retries_local_cleanup_without_resubmitting_and_new_login_resets_acceptance() = runTest {
        val registered = mobileSession.copy(identity = mobileSession.identity.copy(kind = IdentityKind.Registered))
        var failCleanup = true
        val gateway = FakeGateway(restored = registered, clearBehavior = {
            if (failCleanup) error("local_cleanup_failed")
        })
        val coordinator = FamilyGamesCoordinator(gateway, FakeRealtime(), SilentHaptics, this)
        coordinator.startup()
        advanceUntilIdle()
        coordinator.beginAccountDeletion()
        coordinator.confirmAccountDeletionExplanation()
        coordinator.deleteAccount()
        advanceUntilIdle()
        assertEquals(true, coordinator.state.value.accountDeletionFailed)
        assertEquals(AccountDeletionAcceptance.Completed, coordinator.state.value.accountDeletionAcceptance)
        failCleanup = false
        coordinator.deleteAccount()
        advanceUntilIdle()
        assertEquals(1, gateway.deletionCalls)
        assertEquals(AppScreen.Welcome, coordinator.state.value.screen)
        coordinator.signIn("test", "password")
        advanceUntilIdle()
        assertEquals(null, coordinator.state.value.accountDeletionAcceptance)
        assertEquals(null, coordinator.state.value.accountDeletionCleanup)
        coordinator.beginAccountDeletion()
        coordinator.confirmAccountDeletionExplanation()
        coordinator.deleteAccount()
        advanceUntilIdle()
        assertEquals(2, gateway.deletionCalls)
        coordinator.dispose()
    }

    @Test
    fun accepted_deletion_retains_voice_failure_and_retries_only_voice() = runTest {
        val registered = mobileSession.copy(identity = mobileSession.identity.copy(kind = IdentityKind.Registered))
        val teardown = RecordingDeletionTeardown(failVoice = true)
        val gateway = FakeGateway(restored = registered)
        val coordinator = FamilyGamesCoordinator(
            gateway, FakeRealtime(), SilentHaptics, this, accountDeletionTeardown = teardown,
        )
        coordinator.startup()
        advanceUntilIdle()
        coordinator.beginAccountDeletion()
        coordinator.confirmAccountDeletionExplanation()
        coordinator.deleteAccount()
        advanceUntilIdle()

        assertEquals(AccountDeletionCleanupStepState.Failed, coordinator.state.value.accountDeletionCleanup?.voiceLeave)
        assertEquals(AccountDeletionCleanupStepState.Completed, coordinator.state.value.accountDeletionCleanup?.consentEnd)
        assertEquals(AccountDeletionCleanupStepState.Completed, coordinator.state.value.accountDeletionCleanup?.realtimeStop)
        assertEquals(AccountDeletionCleanupStepState.Completed, coordinator.state.value.accountDeletionCleanup?.credentialsClear)
        assertEquals(AppScreen.Home, coordinator.state.value.screen)
        assertEquals(null, coordinator.state.value.mobileSession)
        assertEquals(1, gateway.deletionCalls)

        teardown.failVoice = false
        coordinator.deleteAccount()
        advanceUntilIdle()

        assertEquals(2, teardown.voiceCalls)
        assertEquals(1, teardown.consentCalls)
        assertEquals(1, teardown.realtimeCalls)
        assertEquals(1, teardown.credentialsCalls)
        assertEquals(1, gateway.deletionCalls)
        assertEquals(AppScreen.Welcome, coordinator.state.value.screen)
        assertEquals(true, coordinator.state.value.accountDeletionCleanup?.completed)
        coordinator.dispose()
    }

    @Test
    fun accepted_deletion_retains_consent_failure_and_converges_without_resubmission() = runTest {
        val registered = mobileSession.copy(identity = mobileSession.identity.copy(kind = IdentityKind.Registered))
        val teardown = RecordingDeletionTeardown(failConsent = true)
        val gateway = FakeGateway(restored = registered)
        val coordinator = FamilyGamesCoordinator(
            gateway, FakeRealtime(), SilentHaptics, this, accountDeletionTeardown = teardown,
        )
        coordinator.startup(); advanceUntilIdle()
        coordinator.beginAccountDeletion(); coordinator.confirmAccountDeletionExplanation(); coordinator.deleteAccount()
        advanceUntilIdle()

        assertEquals(AccountDeletionCleanupStepState.Failed, coordinator.state.value.accountDeletionCleanup?.consentEnd)
        assertEquals(AppScreen.Home, coordinator.state.value.screen)
        teardown.failConsent = false
        coordinator.deleteAccount(); advanceUntilIdle()

        assertEquals(1, teardown.voiceCalls)
        assertEquals(2, teardown.consentCalls)
        assertEquals(1, teardown.realtimeCalls)
        assertEquals(1, teardown.credentialsCalls)
        assertEquals(1, gateway.deletionCalls)
        assertEquals(AppScreen.Welcome, coordinator.state.value.screen)
        coordinator.dispose()
    }

    @Test
    fun accepted_deletion_retains_realtime_failure_and_converges_without_resubmission() = runTest {
        val registered = mobileSession.copy(identity = mobileSession.identity.copy(kind = IdentityKind.Registered))
        val teardown = RecordingDeletionTeardown(failRealtime = true)
        val gateway = FakeGateway(restored = registered)
        val coordinator = FamilyGamesCoordinator(
            gateway, FakeRealtime(), SilentHaptics, this, accountDeletionTeardown = teardown,
        )
        coordinator.startup(); advanceUntilIdle()
        coordinator.beginAccountDeletion(); coordinator.confirmAccountDeletionExplanation(); coordinator.deleteAccount()
        advanceUntilIdle()

        assertEquals(AccountDeletionCleanupStepState.Failed, coordinator.state.value.accountDeletionCleanup?.realtimeStop)
        assertEquals(AppScreen.Home, coordinator.state.value.screen)
        teardown.failRealtime = false
        coordinator.deleteAccount(); advanceUntilIdle()

        assertEquals(1, teardown.voiceCalls)
        assertEquals(1, teardown.consentCalls)
        assertEquals(2, teardown.realtimeCalls)
        assertEquals(1, teardown.credentialsCalls)
        assertEquals(1, gateway.deletionCalls)
        assertEquals(AppScreen.Welcome, coordinator.state.value.screen)
        coordinator.dispose()
    }

    @Test
    fun accepted_deletion_keeps_mixed_partial_progress_and_retries_only_failed_steps() = runTest {
        val registered = mobileSession.copy(identity = mobileSession.identity.copy(kind = IdentityKind.Registered))
        val teardown = RecordingDeletionTeardown(failVoice = true, failRealtime = true)
        val gateway = FakeGateway(restored = registered)
        val coordinator = FamilyGamesCoordinator(
            gateway, FakeRealtime(), SilentHaptics, this, accountDeletionTeardown = teardown,
        )
        coordinator.startup(); advanceUntilIdle()
        coordinator.beginAccountDeletion(); coordinator.confirmAccountDeletionExplanation(); coordinator.deleteAccount()
        advanceUntilIdle()

        assertEquals(AccountDeletionCleanupStepState.Failed, coordinator.state.value.accountDeletionCleanup?.voiceLeave)
        assertEquals(AccountDeletionCleanupStepState.Completed, coordinator.state.value.accountDeletionCleanup?.consentEnd)
        assertEquals(AccountDeletionCleanupStepState.Failed, coordinator.state.value.accountDeletionCleanup?.realtimeStop)
        assertEquals(AccountDeletionCleanupStepState.Completed, coordinator.state.value.accountDeletionCleanup?.credentialsClear)
        teardown.failVoice = false
        teardown.failRealtime = false
        coordinator.deleteAccount(); advanceUntilIdle()

        assertEquals(2, teardown.voiceCalls)
        assertEquals(1, teardown.consentCalls)
        assertEquals(2, teardown.realtimeCalls)
        assertEquals(1, teardown.credentialsCalls)
        assertEquals(1, gateway.deletionCalls)
        assertEquals(AppScreen.Welcome, coordinator.state.value.screen)
        coordinator.dispose()
    }

    @Test
    fun credential_cleanup_failure_preserves_session_until_retry_and_new_login_resets_cleanup_state() = runTest {
        val registered = mobileSession.copy(identity = mobileSession.identity.copy(kind = IdentityKind.Registered))
        val teardown = RecordingDeletionTeardown(failCredentials = true)
        val gateway = FakeGateway(restored = registered)
        val coordinator = FamilyGamesCoordinator(
            gateway, FakeRealtime(), SilentHaptics, this, accountDeletionTeardown = teardown,
        )
        coordinator.startup(); advanceUntilIdle()
        coordinator.beginAccountDeletion(); coordinator.confirmAccountDeletionExplanation(); coordinator.deleteAccount()
        advanceUntilIdle()

        assertEquals(AccountDeletionCleanupStepState.Failed, coordinator.state.value.accountDeletionCleanup?.credentialsClear)
        assertEquals(registered, coordinator.state.value.mobileSession)
        assertEquals(AppScreen.Home, coordinator.state.value.screen)
        teardown.failCredentials = false
        coordinator.deleteAccount(); advanceUntilIdle()

        assertEquals(1, teardown.voiceCalls)
        assertEquals(1, teardown.consentCalls)
        assertEquals(1, teardown.realtimeCalls)
        assertEquals(2, teardown.credentialsCalls)
        assertEquals(1, gateway.deletionCalls)
        assertEquals(AppScreen.Welcome, coordinator.state.value.screen)
        coordinator.signIn("test", "password"); advanceUntilIdle()
        assertEquals(null, coordinator.state.value.accountDeletionAcceptance)
        assertEquals(null, coordinator.state.value.accountDeletionCleanup)
        assertEquals(1, teardown.resetCalls)
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
        private val createError: ApiException? = null,
        private val autobusSubmitFailuresBeforeSuccess: Int = 0,
        private val autobusSubmitBehavior: (suspend (Int, AutobusSubmitAnswersRequest) -> GameSessionSnapshot)? = null,
        private val autobusRevealFailuresBeforeSuccess: Int = 0,
        private val autobusVoteBehavior: (suspend (Int, AutobusVoteRequest) -> GameSessionSnapshot)? = null,
        private val resolvedInvitation: GameSessionSnapshot? = null,
        private val federatedSession: MobileSession = mobileSession.copy(
            identity = mobileSession.identity.copy(kind = IdentityKind.Registered),
        ),
        private val deletionBehavior: suspend () -> AccountDeletionAcceptance = { AccountDeletionAcceptance.Completed },
        private val clearBehavior: suspend () -> Unit = {},
    ) : FamilyGamesGateway {
        var deletionCalls = 0
        var clearCalls = 0
        override suspend fun deleteAccount(): AccountDeletionAcceptance { deletionCalls++; return deletionBehavior() }
        override suspend fun clearLocalSession() { clearCalls++; clearBehavior() }
        var lastMove: MoveRequest? = null
        var lastAutobusAnswers: AutobusSubmitAnswersRequest? = null
        var autobusSubmitCalls: Int = 0
        var autobusFinishCalls: Int = 0
        var autobusRevealCalls: Int = 0
        var autobusVoteCalls: Int = 0
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
        var savedProfileName: String? = null
        override suspend fun authenticateFederated(credential: FederatedCredential) = federatedSession
        override suspend fun updateProfile(displayName: String): MobileSession {
            savedProfileName = displayName.trim()
            return federatedSession.copy(identity = federatedSession.identity.copy(displayName = savedProfileName!!))
        }
        override suspend fun login(userNameOrEmail: String, password: String) = restored ?: mobileSession
        override suspend fun register(request: RegistrationRequest) = mobileSession
        override suspend fun logout() = Unit
        override suspend fun activeSession() = active
        override suspend fun createSession(rulesetKey: String): GameSessionSnapshot =
            createError?.let { throw it } ?: game()
        override suspend fun createAutobusSession(
            rounds: Int,
            seconds: Int,
            difficulty: String,
            categories: List<String>,
        ) = game(gameType = "autobus")
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
        override suspend fun submitAutobusAnswers(request: AutobusSubmitAnswersRequest): GameSessionSnapshot {
            autobusSubmitCalls++
            lastAutobusAnswers = request
            if (autobusSubmitCalls <= autobusSubmitFailuresBeforeSuccess) {
                throw ApiException("stale_version", 409, "Refresh authoritative state.")
            }
            autobusSubmitBehavior?.let { return it(autobusSubmitCalls, request) }
            return game(version = request.expectedVersion + 1, gameType = "autobus")
        }
        override suspend fun finishAutobusRound(request: AutobusFinishRoundRequest): GameSessionSnapshot {
            autobusFinishCalls++
            return autobusGame(request.expectedVersion + 1, "grace")
        }
        override suspend fun revealAutobus(request: AutobusRevealRequest): GameSessionSnapshot {
            autobusRevealCalls++
            if (autobusRevealCalls <= autobusRevealFailuresBeforeSuccess) {
                throw ApiException("stale_version", 409, "Refresh authoritative state.")
            }
            return autobusGame(request.expectedVersion + 1, phase = "reveal")
        }
        override suspend fun voteAutobus(request: AutobusVoteRequest): GameSessionSnapshot {
            autobusVoteCalls++
            autobusVoteBehavior?.let { return it(autobusVoteCalls, request) }
            return autobusGame(request.expectedVersion + 1, phase = "reveal")
        }
        override suspend fun requestRematch(sessionId: String) = game(status = "completed")
        override suspend fun acceptRematch(sessionId: String) = game(status = "started")
    }

    private class FakeRealtime(
        private val connectOnStart: Boolean = false,
    ) : GameRealtimeClient {
        private val mutableEvents = MutableSharedFlow<GameRealtimeEvent>(extraBufferCapacity = 4)
        private val mutableConnection = MutableStateFlow(RealtimeConnectionState.Connected)
        private val mutableConsent = MutableSharedFlow<VoiceConsentSignal>(extraBufferCapacity = 4)
        override val connectionState: StateFlow<RealtimeConnectionState> = mutableConnection
        override val events: Flow<GameRealtimeEvent> = mutableEvents
        override val consentSignals: Flow<VoiceConsentSignal> = mutableConsent
        var stopCalls = 0
        var voiceRequests = 0
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
        override suspend fun stop() { stopCalls++ }
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
        fun emitConsent(signal: VoiceConsentSignal) { mutableConsent.tryEmit(signal) }
        fun setConnection(state: RealtimeConnectionState) { mutableConnection.value = state }
        override suspend fun requestVoice(roomId: String, matchNumber: Int): VoiceConsentResult {
            voiceRequests++
            return VoiceConsentResult(roomId, matchNumber, "request-1", membershipId, "member-2", "2099-01-01T00:00:00Z", true)
        }
        override suspend fun iceConfiguration(roomId: String) = VoiceIceConfiguration(
            listOf(IceServer(listOf("stun:test.invalid"))), "2099-01-01T00:00:00Z",
        )
        override suspend fun join(roomId: String, generation: Long) = VoiceJoinResult(
            roomId, generation, membershipId, true, false, "connection-a", "member-2", "connection-b",
        )
    }

    private class FakeCredentials(
        private val result: FederatedCredentialResult = FederatedCredentialResult.Acquired(
            FederatedCredential(
                FederatedIdentityProvider.Google,
                FederatedCredentialType.IdToken,
                "provider-token",
            ),
        ),
    ) : FederatedCredentialProvider {
        override suspend fun acquire(provider: FederatedIdentityProvider) = result
    }

    private class CountingReviewLauncher : ReviewPromptLauncher {
        var launches = 0
        override suspend fun requestReview(beforeNativeLaunch: suspend () -> com.botglobal.mobile.platform.reviews.ReviewPreLaunchDecision): ReviewLaunchOutcome {
            return when (beforeNativeLaunch()) {
                com.botglobal.mobile.platform.reviews.ReviewPreLaunchDecision.Proceed -> {
                    launches++
                    ReviewLaunchOutcome.Launched
                }
                com.botglobal.mobile.platform.reviews.ReviewPreLaunchDecision.Deferred -> ReviewLaunchOutcome.Deferred
                com.botglobal.mobile.platform.reviews.ReviewPreLaunchDecision.Failed -> ReviewLaunchOutcome.Failed
            }
        }
    }

    private class RecordingDeletionTeardown(
        var failVoice: Boolean = false,
        var failConsent: Boolean = false,
        var failRealtime: Boolean = false,
        var failCredentials: Boolean = false,
    ) : FamilyGamesAccountDeletionTeardown {
        var voiceCalls = 0
        var consentCalls = 0
        var realtimeCalls = 0
        var credentialsCalls = 0
        var resetCalls = 0

        override suspend fun leaveVoice() {
            voiceCalls++
            if (failVoice) error("voice_leave_failed")
        }

        override suspend fun endVoiceConsent() {
            consentCalls++
            if (failConsent) error("consent_end_failed")
        }

        override suspend fun stopRealtime() {
            realtimeCalls++
            if (failRealtime) error("realtime_stop_failed")
        }

        override suspend fun clearLocalCredentials() {
            credentialsCalls++
            if (failCredentials) error("credential_cleanup_failed")
        }

        override fun reset() {
            resetCalls++
        }
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

    private class FakeLanguagePreferences(initial: AppLanguage? = null) : ApplicationLanguagePreferences {
        var stored: AppLanguage? = initial

        override fun restore(): AppLanguage? = stored

        override fun save(language: AppLanguage) {
            stored = language
        }
    }

    private class FakeRecentGameSessionPreferences(initial: String? = null) : RecentGameSessionPreferences {
        var stored: String? = initial

        override fun restore(): String? = stored

        override fun save(sessionId: String) {
            stored = sessionId
        }

        override fun clear() {
            stored = null
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

    private class RecordingMicrophonePermission : PermissionController {
        var requests = 0
        override suspend fun state(permission: PermissionKind) = PermissionState.Unknown
        override suspend fun requestAfterExplanation(permission: PermissionKind): PermissionState {
            assertEquals(PermissionKind.Microphone, permission)
            requests++
            return PermissionState.Granted
        }
    }

    private class RecordingVoiceMediaFactory : VoiceMediaPeerFactory {
        var creations = 0
        var closes = 0
        override fun create(configuration: VoiceIceConfiguration, generation: Long, listener: VoiceMediaPeerListener): VoiceMediaPeer {
            creations++
            return object : VoiceMediaPeer {
                override suspend fun createOffer() = "offer"
                override suspend fun acceptOfferAndCreateAnswer(sessionDescription: String) = "answer"
                override suspend fun acceptAnswer(sessionDescription: String) = Unit
                override suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) = Unit
                override fun setMuted(muted: Boolean) = Unit
                override suspend fun close() { closes++ }
            }
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
            voiceEnabled: Boolean = false,
        ) = GameSessionSnapshot(
            sessionId = "session-1",
            joinCode = "ABC123",
            gameType = gameType,
            status = status,
            matchNumber = matchNumber,
            ruleset = RulesetSnapshot("classic-3x3", 3, 3, 2, null, true, voiceEnabled),
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

        private fun autobusGame(
            version: Long,
            phase: String = "active",
            graceEndsAtUtc: String? = null,
            ownAnswer: String? = null,
            currentRound: Int = 1,
            revealCategoryKey: String? = null,
            voteDeadlineAtUtc: String? = null,
        ) = game(
            version = version,
            status = "started",
            gameType = "autobus",
        ).copy(
            autobus = AutobusSnapshot(
                schemaVersion = 1,
                difficulty = "easy",
                roundCount = 5,
                roundSeconds = 60,
                categories = listOf(AutobusCategorySnapshot("boy_name", "اسم ولد", "Boy name")),
                currentRound = currentRound,
                currentLetter = "أ",
                phase = phase,
                graceEndsAtUtc = graceEndsAtUtc,
                voteDeadlineAtUtc = voteDeadlineAtUtc,
                revealCategoryKey = revealCategoryKey,
                answers = ownAnswer?.let {
                    listOf(
                        AutobusAnswerSnapshot(
                            playerMembershipId = membershipId,
                            categoryKey = "boy_name",
                            displayAnswer = it,
                            outcome = "accepted",
                            reasonCode = "accepted",
                            friendlyMessageCode = "accepted",
                            canonicalValue = it,
                            score = 10,
                            scored = true,
                            needsVote = false,
                            duplicate = false,
                        ),
                    )
                }.orEmpty(),
            ),
        )
    }
}
