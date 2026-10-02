package com.botglobal.nqrb.app.state

import com.botglobal.mobile.platform.calling.CallAudioRoute
import com.botglobal.mobile.platform.calling.CallDirection
import com.botglobal.mobile.platform.calling.CallId
import com.botglobal.mobile.platform.calling.CallParticipant
import com.botglobal.mobile.platform.calling.CallableParticipant
import com.botglobal.mobile.platform.calling.CallingDirectory
import com.botglobal.mobile.platform.calling.CallingDirectoryController
import com.botglobal.mobile.platform.calling.CallingDirectoryStatus
import com.botglobal.mobile.platform.calling.CallPlatformAction
import com.botglobal.mobile.platform.calling.CallPlatformLifecycle
import com.botglobal.mobile.platform.calling.CallSessionController
import com.botglobal.mobile.platform.calling.CallSignaling
import com.botglobal.mobile.platform.calling.CallSignalingEvent
import com.botglobal.mobile.platform.calling.CallState
import com.botglobal.mobile.platform.calling.CallTerminationReason
import com.botglobal.mobile.platform.calling.CallActivityController
import com.botglobal.mobile.platform.calling.CallActivityGateway
import com.botglobal.mobile.platform.calling.CallHistoryDetail
import com.botglobal.mobile.platform.calling.CallHistoryFilter
import com.botglobal.mobile.platform.calling.CallHistoryPage
import com.botglobal.mobile.platform.calling.FinalCallUsage
import com.botglobal.mobile.platform.calling.PendingCallUsageStore
import com.botglobal.mobile.platform.calling.UsagePeriod
import com.botglobal.mobile.platform.calling.OutgoingCallRequest
import com.botglobal.mobile.platform.calling.StartedCall
import com.botglobal.mobile.platform.device.PermissionController
import com.botglobal.mobile.platform.device.PermissionKind
import com.botglobal.mobile.platform.device.PermissionState
import com.botglobal.mobile.platform.identity.ApplicationIdentity
import com.botglobal.mobile.platform.identity.FederatedAuthenticationState
import com.botglobal.mobile.platform.identity.FederatedCredential
import com.botglobal.mobile.platform.identity.FederatedCredentialProvider
import com.botglobal.mobile.platform.identity.FederatedCredentialResult
import com.botglobal.mobile.platform.identity.FederatedCredentialType
import com.botglobal.mobile.platform.identity.FederatedIdentityController
import com.botglobal.mobile.platform.identity.FederatedIdentityGateway
import com.botglobal.mobile.platform.identity.FederatedIdentityProvider
import com.botglobal.mobile.platform.identity.FederatedSignInResult
import com.botglobal.mobile.platform.identity.IdentityKind
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.localization.ContentDirection
import com.botglobal.mobile.platform.notifications.PushRegistrationLifecycle
import com.botglobal.mobile.platform.notifications.PushRegistrationOutcome
import com.botglobal.nqrb.app.data.NqrbAccountDeletionGateway
import com.botglobal.nqrb.app.data.NqrbAccountDeletionOutcome
import com.botglobal.nqrb.app.data.NqrbContact
import com.botglobal.nqrb.app.data.NqrbContactBookGateway
import com.botglobal.nqrb.app.data.NqrbContactBookResult
import com.botglobal.nqrb.app.data.NqrbContactInvite
import com.botglobal.nqrb.app.data.NqrbContactInviteAcceptResult
import com.botglobal.nqrb.app.data.NqrbContactInviteCreateResult
import com.botglobal.nqrb.app.data.NqrbContactInvitePreviewResult
import com.botglobal.nqrb.app.data.NqrbContactMutationResult
import com.botglobal.nqrb.app.data.NqrbContactPage
import com.botglobal.nqrb.app.data.NqrbGuestCallInvite
import com.botglobal.nqrb.app.data.NqrbGuestCallInviteCreateResult
import com.botglobal.nqrb.app.data.NqrbGuestCallInviteRevokeResult
import com.botglobal.mobile.platform.voice.VoiceRoomController
import com.botglobal.mobile.platform.voice.VoiceRoomSnapshot
import com.botglobal.mobile.platform.voice.VoiceRoomState
import com.botglobal.mobile.platform.voice.VoiceMediaStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class NqrbAppStateTests {
    @Test
    fun startup_remains_restoring_until_authoritative_session_result_is_applied() = runTest {
        val restored = CompletableDeferred<MobileSession?>()
        val gateway = DeferredIdentityGateway(restored)
        val state = NqrbAppState(
            identity = FederatedIdentityController(FixedCredentials, gateway),
        )

        backgroundScope.launch { state.startup() }
        runCurrent()

        assertEquals(NqrbStartupState.RestoringSession, state.startupState.value)
        assertEquals(NqrbDestination.SignIn, state.navigation.current)

        restored.complete(session())
        runCurrent()

        assertEquals(NqrbStartupState.Ready, state.startupState.value)
        assertEquals(NqrbDestination.Home, state.navigation.current)
    }

    @Test
    fun failed_restore_reveals_sign_in_only_after_bootstrap_finishes() = runTest {
        val state = state(restored = null)

        assertEquals(NqrbStartupState.RestoringSession, state.startupState.value)
        state.startup()

        assertEquals(NqrbStartupState.Ready, state.startupState.value)
        assertEquals(NqrbDestination.SignIn, state.navigation.current)
    }

    @Test
    fun repeated_ui_startup_does_not_repeat_session_restoration() = runTest {
        val gateway = CountingIdentityGateway(session())
        val state = NqrbAppState(
            identity = FederatedIdentityController(FixedCredentials, gateway),
        )

        state.startup()
        state.startup()

        assertEquals(1, gateway.restores)
        assertEquals(NqrbDestination.Home, state.navigation.current)
    }

    @Test
    fun incoming_call_bootstrap_remains_independent_of_compose_session_restoration() = runTest {
        val restored = CompletableDeferred<MobileSession?>()
        val signaling = RecordingCallSignaling()
        val calling = CallSessionController(
            backgroundScope,
            signaling,
            RecordingVoiceRoom(),
            RecordingCallPlatform(),
        )
        val state = NqrbAppState(
            identity = FederatedIdentityController(FixedCredentials, DeferredIdentityGateway(restored)),
            calling = calling,
        )

        backgroundScope.launch { state.startup() }
        runCurrent()
        signaling.emit(
            CallSignalingEvent.IncomingOffered(
                CallId("incoming-during-restore"),
                "nqrb",
                CallParticipant("caller", "Caller"),
            ),
        )
        runCurrent()

        assertEquals(NqrbStartupState.RestoringSession, state.startupState.value)
        assertEquals(CallState.Ringing, calling.state.value.state)
    }

    @Test
    fun startsSignedOutInArabicWithoutPhoneIdentityGate() = runTest {
        val state = state()
        state.startup()

        assertEquals("ar", state.locale.state.value.languageTag)
        assertEquals(ContentDirection.RightToLeft, state.locale.state.value.direction)
        assertEquals(NqrbDestination.SignIn, state.navigation.current)
        assertFalse(state.canUseHome())
    }

    @Test
    fun authoritativeSignInContinuesDirectlyToContactFirstHome() = runTest {
        val state = state(signIn = FederatedSignInResult.Authenticated(session()))
        state.startup()
        state.signInWithGoogle()
        assertEquals(NqrbDestination.Home, state.navigation.current)
        assertTrue(state.canUseHome())
    }

    @Test
    fun phoneContactsAreNotPartOfAuthenticatedHome() = runTest {
        val state = state(
            restored = session(),
        )
        state.startup()

        assertEquals(NqrbDestination.Home, state.navigation.current)
        assertTrue(state.canUseHome())
    }

    @Test
    fun restoredCentralSessionBypassesSignInAndPhoneIdentityIsNotRequired() = runTest {
        val push = RecordingPushLifecycle()
        val state = state(restored = session(), push = push)
        state.startup()

        assertEquals(NqrbDestination.Home, state.navigation.current)
        assertTrue(state.canUseHome())
        assertEquals(1, push.activations)
    }

    @Test
    fun pushRegistrationFailureNeverBlocksRestoredIdentityOrHome() = runTest {
        val state = state(
            restored = session(),
            push = ThrowingPushLifecycle,
        )

        state.startup()

        assertEquals(NqrbDestination.Home, state.navigation.current)
        assertTrue(state.canUseHome())
    }

    @Test
    fun backendRejectionDoesNotAuthenticate() = runTest {
        val state = state(signIn = FederatedSignInResult.Rejected)
        state.startup()
        state.signInWithGoogle()

        assertTrue(state.identity.state.value is FederatedAuthenticationState.AuthenticationError)
        assertEquals(NqrbDestination.SignIn, state.navigation.current)
        assertFalse(state.selectTopLevel(NqrbDestination.Home))
    }

    @Test
    fun settingsBackAndLogoutRemainCoherent() = runTest {
        val push = RecordingPushLifecycle()
        val state = state(restored = session(), push = push)
        state.startup()
        assertTrue(state.selectTopLevel(NqrbDestination.People))
        state.openSettings()
        assertTrue(state.navigation.navigateBack())
        assertEquals(NqrbDestination.People, state.navigation.current)

        state.logout()
        assertEquals(NqrbDestination.SignIn, state.navigation.current)
        assertFalse(state.navigation.navigateBack())
        assertEquals(1, push.deactivations)
    }

    @Test
    fun failedUnpairPreservesAuthenticatedStateAndAllowsRetry() = runTest {
        val push = RecordingPushLifecycle(PushRegistrationOutcome.RetryableFailure)
        val state = state(restored = session(), push = push)
        state.startup()

        state.logout()

        assertTrue(state.identity.state.value is FederatedAuthenticationState.SignedIn)
        assertEquals(NqrbDestination.Home, state.navigation.current)
        assertEquals(NqrbAccountActionState.SignOutFailed, state.accountActionState.value)
        assertEquals(1, push.deactivations)
    }

    @Test
    fun acceptedDeletionClearsLocalStateAndReturnsToSignedOutFlow() = runTest {
        val push = RecordingPushLifecycle()
        val cleaner = RecordingLocalCleaner()
        val state = state(
            restored = session(),
            push = push,
            accountDeletion = NqrbAccountDeletionGateway { NqrbAccountDeletionOutcome.Accepted },
            localCleaner = cleaner,
        )
        state.startup()

        state.deleteAccount()

        assertEquals(FederatedAuthenticationState.SignedOut, state.identity.state.value)
        assertEquals(NqrbDestination.SignIn, state.navigation.current)
        assertEquals(1, cleaner.clears)
        assertEquals(1, push.localClears)
        assertEquals(NqrbAccountActionState.Idle, state.accountActionState.value)
    }

    @Test
    fun failedDeletionPreservesRecoverableAuthenticatedState() = runTest {
        val push = RecordingPushLifecycle()
        val cleaner = RecordingLocalCleaner()
        val state = state(
            restored = session(),
            push = push,
            accountDeletion = NqrbAccountDeletionGateway { NqrbAccountDeletionOutcome.RetryableFailure },
            localCleaner = cleaner,
        )
        state.startup()

        state.deleteAccount()

        assertTrue(state.identity.state.value is FederatedAuthenticationState.SignedIn)
        assertEquals(NqrbDestination.Home, state.navigation.current)
        assertEquals(0, cleaner.clears)
        assertEquals(0, push.localClears)
        assertEquals(NqrbAccountActionState.DeletionFailed, state.accountActionState.value)
    }

    @Test
    fun deletionSubmissionIsSingleFlight() = runTest {
        val response = CompletableDeferred<NqrbAccountDeletionOutcome>()
        var calls = 0
        val state = state(
            restored = session(),
            accountDeletion = NqrbAccountDeletionGateway {
                calls++
                response.await()
            },
        )
        state.startup()

        backgroundScope.launch { state.deleteAccount() }
        runCurrent()
        backgroundScope.launch { state.deleteAccount() }
        runCurrent()

        assertEquals(1, calls)
        assertEquals(NqrbAccountActionState.Deleting, state.accountActionState.value)
        response.complete(NqrbAccountDeletionOutcome.RetryableFailure)
        runCurrent()
        assertEquals(NqrbAccountActionState.DeletionFailed, state.accountActionState.value)
    }

    @Test
    fun microphone_is_explained_just_in_time_before_android_permission() = runTest {
        val state = NqrbAppState(
            identity = FederatedIdentityController(FixedCredentials, FixedIdentityGateway(session(), FederatedSignInResult.Rejected)),
            permissions = FixedPermission(PermissionState.Denied),
            callActionScope = backgroundScope,
        )
        state.startup()

        state.requestOutgoingCall(
            CallableParticipant("known-nqrb-member", "Known NQRB user"),
        )
        runCurrent()

        assertTrue(state.microphoneExplanationVisible.value)
        assertEquals(com.botglobal.mobile.platform.calling.CallState.Idle, state.calling.state.value.state)
    }

    @Test
    fun directory_waits_for_authoritative_session_then_loads_for_authenticated_member() = runTest {
        val restored = CompletableDeferred<MobileSession?>()
        val directory = RecordingDirectory(
            listOf(CallableParticipant("remote", "Remote user")),
        )
        val state = NqrbAppState(
            identity = FederatedIdentityController(
                FixedCredentials,
                DeferredIdentityGateway(restored),
            ),
            callingDirectory = CallingDirectoryController(directory),
            callActionScope = backgroundScope,
        )

        backgroundScope.launch { state.startup() }
        runCurrent()

        assertEquals(0, directory.loads)
        restored.complete(session())
        runCurrent()

        assertEquals(1, directory.loads)
        assertEquals(CallingDirectoryStatus.Ready, state.callingDirectory.state.value.status)
    }

    @Test
    fun directory_error_has_no_call_fallback_and_explicit_retry_recovers() = runTest {
        val signaling = RecordingCallSignaling()
        val calling = CallSessionController(
            backgroundScope,
            signaling,
            RecordingVoiceRoom(),
            RecordingCallPlatform(),
        )
        val directory = RetryDirectory()
        val state = NqrbAppState(
            identity = FederatedIdentityController(
                FixedCredentials,
                FixedIdentityGateway(session(), FederatedSignInResult.Rejected),
            ),
            calling = calling,
            callingDirectory = CallingDirectoryController(directory),
            permissions = FixedPermission(PermissionState.Granted),
            callActionScope = backgroundScope,
        )

        state.startup()
        runCurrent()

        assertEquals(CallingDirectoryStatus.Error, state.callingDirectory.state.value.status)
        assertEquals(0, signaling.startedRequests.size)

        state.refreshCallingDirectory()
        runCurrent()

        assertEquals(CallingDirectoryStatus.Ready, state.callingDirectory.state.value.status)
        assertEquals(0, signaling.startedRequests.size)
    }

    @Test
    fun returning_to_people_and_home_refreshes_the_authenticated_directory_without_polling() = runTest {
        val directory = RecordingDirectory(emptyList())
        val state = NqrbAppState(
            identity = FederatedIdentityController(
                FixedCredentials,
                FixedIdentityGateway(session(), FederatedSignInResult.Rejected),
            ),
            callingDirectory = CallingDirectoryController(directory),
            callActionScope = backgroundScope,
        )
        state.startup()
        runCurrent()
        assertEquals(1, directory.loads)

        assertTrue(state.selectTopLevel(NqrbDestination.People))
        runCurrent()
        assertEquals(2, directory.loads)

        assertTrue(state.selectTopLevel(NqrbDestination.Home))
        runCurrent()
        assertEquals(3, directory.loads)
    }

    @Test
    fun call_action_preserves_selected_participant_id_and_display_as_one_object() = runTest {
        val signaling = RecordingCallSignaling()
        val calling = CallSessionController(
            backgroundScope,
            signaling,
            RecordingVoiceRoom(),
            RecordingCallPlatform(),
        )
        val state = NqrbAppState(
            identity = FederatedIdentityController(
                FixedCredentials,
                FixedIdentityGateway(session(), FederatedSignInResult.Rejected),
            ),
            calling = calling,
            permissions = FixedPermission(PermissionState.Granted),
            callActionScope = backgroundScope,
        )
        state.startup()
        val selected = CallableParticipant("remote-membership", "Remote display")

        state.requestOutgoingCall(selected)
        runCurrent()

        val request = signaling.startedRequests.single()
        assertEquals(selected.membershipId, request.callee.membershipId)
        assertEquals(selected.displayName, request.callee.displayName)
    }

    @Test
    fun client_defense_ignores_an_accidental_self_participant() = runTest {
        val signaling = RecordingCallSignaling()
        val state = NqrbAppState(
            identity = FederatedIdentityController(
                FixedCredentials,
                FixedIdentityGateway(session(), FederatedSignInResult.Rejected),
            ),
            calling = CallSessionController(
                backgroundScope,
                signaling,
                RecordingVoiceRoom(),
                RecordingCallPlatform(),
            ),
            permissions = FixedPermission(PermissionState.Granted),
            callActionScope = backgroundScope,
        )
        state.startup()

        state.requestOutgoingCall(CallableParticipant("membership", "Current user"))
        runCurrent()

        assertEquals(0, signaling.startedRequests.size)
    }

    @Test
    fun foreground_compose_decline_uses_authoritative_reject_once_without_starting_media() = runTest {
        val signaling = RecordingCallSignaling()
        val voice = RecordingVoiceRoom()
        val platform = RecordingCallPlatform()
        val calling = CallSessionController(backgroundScope, signaling, voice, platform)
        val state = NqrbAppState(calling = calling, callActionScope = backgroundScope)
        val offer = CallSignalingEvent.IncomingOffered(
            CallId("incoming"),
            "nqrb",
            CallParticipant("caller", "Caller"),
        )

        runCurrent()
        signaling.emit(offer)
        runCurrent()
        signaling.emit(offer)
        runCurrent()
        state.rejectIncomingCall()
        state.rejectIncomingCall()
        runCurrent()

        assertEquals(CallState.Rejected, calling.state.value.state)
        assertEquals(CallTerminationReason.Rejected, calling.state.value.terminationReason)
        assertTrue(calling.state.value.networkUsage.isFinal)
        assertEquals(1, signaling.rejects)
        assertEquals(0, signaling.ends)
        assertEquals(0, signaling.answers)
        assertEquals(0, voice.joins)
        assertEquals(1, platform.presentations)
        assertEquals(0, platform.starts)
        assertEquals(listOf(CallTerminationReason.Rejected), platform.endReasons)
    }

    @Test
    fun terminal_measured_call_usage_flows_once_through_the_durable_activity_outbox() = runTest {
        val signaling = RecordingCallSignaling()
        val voice = RecordingVoiceRoom()
        val gateway = RecordingCallActivityGateway()
        val pending = RecordingPendingUsageStore()
        val state = NqrbAppState(
            identity = FederatedIdentityController(FixedCredentials, FixedIdentityGateway(session(), FederatedSignInResult.Rejected)),
            calling = CallSessionController(backgroundScope, signaling, voice, RecordingCallPlatform()),
            callActivity = CallActivityController(gateway, pending),
            permissions = FixedPermission(PermissionState.Granted),
            callActionScope = backgroundScope,
        )
        state.startup()
        state.requestOutgoingCall(CallableParticipant("remote", "Remote"))
        runCurrent()
        voice.snapshot.value = VoiceRoomSnapshot(
            state = VoiceRoomState.Connected,
            stats = VoiceMediaStats(outboundBytes = 100, inboundBytes = 200, available = true),
        )
        runCurrent()
        voice.snapshot.value = VoiceRoomSnapshot(
            state = VoiceRoomState.Connected,
            stats = VoiceMediaStats(outboundBytes = 1_100, inboundBytes = 2_200, available = true),
        )
        runCurrent()

        state.endCall()
        runCurrent()

        val report = gateway.finalized.single()
        assertEquals("outgoing", report.callId)
        assertEquals(1_000, report.bytesSent)
        assertEquals(2_000, report.bytesReceived)
        assertEquals("membership", report.ownerMembershipId)
        assertEquals(emptyList(), pending.load())
    }

    @Test
    fun adding_contact_from_history_is_single_session_feedback_and_duplicate_suppressed() = runTest {
        val response = CompletableDeferred<NqrbContactMutationResult>()
        val contactGateway = RecordingContactBookGateway(response)
        val state = NqrbAppState(
            identity = FederatedIdentityController(FixedCredentials, FixedIdentityGateway(session(), FederatedSignInResult.Rejected)),
            contactBook = NqrbContactBookController(contactGateway),
            callActionScope = backgroundScope,
        )
        state.startup()

        state.addNqrbContactFromCallHistory("call-1", "remote")
        state.addNqrbContactFromCallHistory("call-2", "remote")
        runCurrent()

        assertEquals(1, contactGateway.historyAdds)
        response.complete(NqrbContactMutationResult.Saved(NqrbContact("remote", "Remote")))
        runCurrent()

        assertEquals(setOf("remote"), state.addedHistoryContactCalls.value)
        state.addNqrbContactFromCallHistory("call-2", "remote")
        runCurrent()
        assertEquals(1, contactGateway.historyAdds)

        state.removeNqrbContact("remote")
        runCurrent()
        assertTrue(state.addedHistoryContactCalls.value.isEmpty())

        state.addNqrbContactFromCallHistory("call-2", "remote")
        runCurrent()
        assertEquals(2, contactGateway.historyAdds)
    }

    @Test
    fun guest_button_renews_an_expired_session_without_restarting_the_app() = runTest {
        val expired = session().copy(accessExpiresAtUtc = "2000-01-01T00:00:00Z")
        val renewed = session().copy(accessToken = "renewed-access", refreshToken = "renewed-refresh")
        var restores = 0
        val identityGateway = object : FederatedIdentityGateway {
            override suspend fun restore(): MobileSession? = if (++restores == 1) expired else renewed
            override suspend fun authenticate(credential: FederatedCredential) = FederatedSignInResult.Failed
            override suspend fun logout() = Unit
        }
        val contacts = RecordingContactBookGateway(CompletableDeferred())
        val state = NqrbAppState(
            identity = FederatedIdentityController(FixedCredentials, identityGateway),
            contactBook = NqrbContactBookController(contacts),
            callActionScope = backgroundScope,
        )
        state.startup()

        state.createNqrbGuestCallInvite()
        runCurrent()

        assertEquals(2, restores)
        assertEquals(listOf(renewed), contacts.guestInviteSessions)
        assertEquals(NqrbGuestCallInviteCreateState.Ready, state.contactBook.state.value.guestCallInviteCreateState)
    }

    @Test
    fun foreground_retries_transient_session_refresh_without_restarting_or_using_expired_token() = runTest {
        val expired = session().copy(accessExpiresAtUtc = "2000-01-01T00:00:00Z")
        val renewed = session().copy(accessToken = "renewed-access", refreshToken = "renewed-refresh")
        var restores = 0
        val identityGateway = object : FederatedIdentityGateway {
            override suspend fun restore(): MobileSession? = when (++restores) {
                1 -> expired
                2 -> throw IllegalStateException("temporary network failure")
                else -> renewed
            }
            override suspend fun authenticate(credential: FederatedCredential) = FederatedSignInResult.Failed
            override suspend fun logout() = Unit
        }
        val requestedSessions = mutableListOf<MobileSession>()
        val contacts = object : NqrbContactBookGateway by RecordingContactBookGateway(CompletableDeferred()) {
            override suspend fun listContacts(session: MobileSession, page: Int): NqrbContactBookResult {
                requestedSessions += session
                return NqrbContactBookResult.Available(NqrbContactPage(emptyList(), page, 20, false))
            }
        }
        val state = NqrbAppState(
            identity = FederatedIdentityController(FixedCredentials, identityGateway),
            contactBook = NqrbContactBookController(contacts),
            callActionScope = backgroundScope,
        )
        state.startup()

        state.onForeground()
        runCurrent()
        assertEquals(listOf(expired), requestedSessions)
        assertTrue(state.canUseHome())

        advanceTimeBy(5 * 60 * 1000L)
        runCurrent()
        assertEquals(listOf(expired, renewed), requestedSessions)
        assertEquals(3, restores)
    }

    @Test
    fun returning_to_foreground_restores_call_signaling_after_a_network_interruption() = runTest {
        val signaling = RecordingCallSignaling()
        val calling = CallSessionController(backgroundScope, signaling, RecordingVoiceRoom(), RecordingCallPlatform())
        val state = NqrbAppState(
            identity = FederatedIdentityController(FixedCredentials, FixedIdentityGateway(session(), FederatedSignInResult.Rejected)),
            calling = calling,
            callActionScope = backgroundScope,
        )
        state.startup()
        assertEquals(1, signaling.connects)

        state.onForeground()
        runCurrent()
        assertEquals(2, signaling.connects)

        state.onBackground()
        state.onForeground()
        runCurrent()
        assertEquals(3, signaling.connects)
    }

    private fun state(
        restored: MobileSession? = null,
        signIn: FederatedSignInResult = FederatedSignInResult.Rejected,
        permission: PermissionState = PermissionState.Unknown,
        push: PushRegistrationLifecycle = RecordingPushLifecycle(),
        accountDeletion: NqrbAccountDeletionGateway = NqrbAccountDeletionGateway {
            NqrbAccountDeletionOutcome.RetryableFailure
        },
        localCleaner: NqrbLocalAccountDataCleaner = RecordingLocalCleaner(),
    ) = NqrbAppState(
        identity = FederatedIdentityController(
            credentials = FixedCredentials,
            gateway = FixedIdentityGateway(restored, signIn),
        ),
        push = push,
        accountDeletion = accountDeletion,
        localAccountDataCleaner = localCleaner,
    )

    private object FixedCredentials : FederatedCredentialProvider {
        override suspend fun acquire(provider: FederatedIdentityProvider) = FederatedCredentialResult.Acquired(
            FederatedCredential(provider, FederatedCredentialType.IdToken, "transient-test-token"),
        )
    }

    private class FixedIdentityGateway(
        private val restored: MobileSession?,
        private val result: FederatedSignInResult,
    ) : FederatedIdentityGateway {
        override suspend fun restore() = restored
        override suspend fun authenticate(credential: FederatedCredential) = result
        override suspend fun logout() = Unit
    }

    private class DeferredIdentityGateway(
        private val restored: CompletableDeferred<MobileSession?>,
    ) : FederatedIdentityGateway {
        override suspend fun restore() = restored.await()
        override suspend fun authenticate(credential: FederatedCredential) = FederatedSignInResult.Failed
        override suspend fun logout() = Unit
    }

    private class CountingIdentityGateway(
        private val restored: MobileSession?,
    ) : FederatedIdentityGateway {
        var restores = 0
        override suspend fun restore(): MobileSession? {
            restores++
            return restored
        }
        override suspend fun authenticate(credential: FederatedCredential) = FederatedSignInResult.Failed
        override suspend fun logout() = Unit
    }

    private class FixedPermission(private val result: PermissionState) : PermissionController {
        override suspend fun state(permission: PermissionKind) = result
        override suspend fun requestAfterExplanation(permission: PermissionKind) = result
    }


    private class RecordingPushLifecycle(
        private val deactivationOutcome: PushRegistrationOutcome = PushRegistrationOutcome.Unregistered,
    ) : PushRegistrationLifecycle {
        var activations = 0
        var deactivations = 0
        var localClears = 0
        override suspend fun activate() { activations++ }
        override suspend fun deactivate(): PushRegistrationOutcome {
            deactivations++
            return deactivationOutcome
        }
        override suspend fun clearLocalState() { localClears++ }
    }

    private object ThrowingPushLifecycle : PushRegistrationLifecycle {
        override suspend fun activate() = error("Synthetic push registration failure")
        override suspend fun deactivate() = error("Synthetic push invalidation failure")
        override suspend fun clearLocalState() = Unit
    }

    private class RecordingLocalCleaner : NqrbLocalAccountDataCleaner {
        var clears = 0
        override suspend fun clear() { clears++ }
    }

    private class RecordingDirectory(
        private val participants: List<CallableParticipant>,
    ) : CallingDirectory {
        var loads = 0

        override suspend fun loadCallableParticipants(): List<CallableParticipant> {
            loads++
            return participants
        }
    }

    private class RetryDirectory : CallingDirectory {
        private var loads = 0

        override suspend fun loadCallableParticipants(): List<CallableParticipant> {
            loads++
            if (loads == 1) error("Synthetic directory failure")
            return listOf(CallableParticipant("remote", "Remote user"))
        }
    }

    private class RecordingCallSignaling : CallSignaling {
        private val mutableEvents = MutableSharedFlow<CallSignalingEvent>(extraBufferCapacity = 4)
        override val events = mutableEvents
        var answers = 0
        var rejects = 0
        var ends = 0
        var connects = 0
        val startedRequests = mutableListOf<OutgoingCallRequest>()

        override suspend fun connect() {
            connects++
        }

        override suspend fun startOutgoing(request: OutgoingCallRequest): StartedCall {
            startedRequests += request
            return StartedCall(CallId("outgoing"), request.callee)
        }

        override suspend fun answer(callId: CallId) {
            answers++
        }

        override suspend fun reject(callId: CallId) {
            rejects++
        }

        override suspend fun end(callId: CallId, reason: CallTerminationReason) {
            ends++
        }

        suspend fun emit(event: CallSignalingEvent) {
            mutableEvents.emit(event)
        }
    }

    private class RecordingVoiceRoom : VoiceRoomController {
        override val snapshot = MutableStateFlow(VoiceRoomSnapshot())
        var joins = 0

        override suspend fun join(roomId: String) {
            joins++
        }

        override suspend fun leave() = Unit
        override suspend fun setMuted(muted: Boolean) = Unit
        override suspend fun signalingInterrupted() = Unit
        override suspend fun signalingRecovered() = Unit
    }

    private class RecordingPendingUsageStore : PendingCallUsageStore {
        private val reports = linkedMapOf<String, FinalCallUsage>()
        override suspend fun load() = reports.values.toList()
        override suspend fun save(usage: FinalCallUsage) { reports[usage.callId] = usage }
        override suspend fun remove(callId: String) { reports.remove(callId) }
    }

    private class RecordingCallActivityGateway : CallActivityGateway {
        val finalized = mutableListOf<FinalCallUsage>()
        override suspend fun history(page: Int, pageSize: Int, filter: CallHistoryFilter) = CallHistoryPage(emptyList(), page, pageSize, false)
        override suspend fun detail(callId: String): CallHistoryDetail? = null
        override suspend fun finalizeUsage(usage: FinalCallUsage) { finalized += usage }
        override suspend fun currentUsage() = UsagePeriod("period", "2026-09-01T00:00:00Z", null, 0, 0, null, null)
        override suspend fun resetUsage() = currentUsage()
        override suspend fun scheduleUsageReset(schedule: com.botglobal.mobile.platform.calling.UsageResetSchedule) = currentUsage()
    }

    private class RecordingCallPlatform : CallPlatformLifecycle {
        override val actions = MutableSharedFlow<CallPlatformAction>(extraBufferCapacity = 2)
        var presentations = 0
        var starts = 0
        val endReasons = mutableListOf<CallTerminationReason>()

        override suspend fun presentIncoming(callId: CallId, participant: CallParticipant) {
            presentations++
        }

        override suspend fun start(callId: CallId, participant: CallParticipant, direction: CallDirection) {
            starts++
        }

        override suspend fun markActive() = Unit
        override suspend fun requestRoute(route: CallAudioRoute) = route
        override suspend fun end(reason: CallTerminationReason) {
            endReasons += reason
        }
    }

    private class RecordingContactBookGateway(
        private val historyAddResult: CompletableDeferred<NqrbContactMutationResult>,
    ) : NqrbContactBookGateway {
        var historyAdds = 0
        val guestInviteSessions = mutableListOf<MobileSession>()
        override suspend fun listContacts(session: MobileSession, page: Int) =
            NqrbContactBookResult.Available(NqrbContactPage(emptyList(), page, 20, false))
        override suspend fun searchUsers(session: MobileSession, query: String, page: Int) =
            NqrbContactBookResult.Available(NqrbContactPage(emptyList(), page, 20, false))
        override suspend fun addContact(session: MobileSession, membershipId: String) =
            NqrbContactMutationResult.Saved(NqrbContact(membershipId, "Saved"))
        override suspend fun addContactFromCallHistory(session: MobileSession, callId: String): NqrbContactMutationResult {
            historyAdds++
            return historyAddResult.await()
        }
        override suspend fun removeContact(session: MobileSession, membershipId: String) = NqrbContactMutationResult.Removed
        override suspend fun updateContactNickname(session: MobileSession, membershipId: String, nickname: String?) =
            NqrbContactMutationResult.Saved(NqrbContact(membershipId, "Saved", nickname))
        override suspend fun createInvite(session: MobileSession) =
            NqrbContactInviteCreateResult.Created(NqrbContactInvite("code", "nqrb://invite/code", "2099-01-01T00:00:00Z"))
        override suspend fun createGuestCallInvite(session: MobileSession): NqrbGuestCallInviteCreateResult {
            guestInviteSessions += session
            return NqrbGuestCallInviteCreateResult.Created(
                NqrbGuestCallInvite("invite", "https://example.test", "2099-01-01T00:00:00Z"),
            )
        }
        override suspend fun revokeGuestCallInvite(session: MobileSession, inviteId: String) =
            NqrbGuestCallInviteRevokeResult.Revoked
        override suspend fun previewInvite(session: MobileSession, code: String) = NqrbContactInvitePreviewResult.Invalid
        override suspend fun acceptInvite(session: MobileSession, code: String) = NqrbContactInviteAcceptResult.Invalid
    }

    private fun session() = MobileSession(
        "platform-access", "2099-01-01T00:00:00Z", "platform-refresh", "2099-02-01T00:00:00Z",
        ApplicationIdentity("membership", "subject", "NQRB User", IdentityKind.Registered, "nqrb"),
    )
}
