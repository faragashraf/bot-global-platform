package com.botglobal.nqrb.app.state

import com.botglobal.mobile.platform.appearance.AppearanceController
import com.botglobal.mobile.platform.calling.CallParticipant
import com.botglobal.mobile.platform.calling.CallAudioRoute
import com.botglobal.mobile.platform.calling.CallableParticipant
import com.botglobal.mobile.platform.calling.CallingDirectoryController
import com.botglobal.mobile.platform.calling.CallSessionController
import com.botglobal.mobile.platform.calling.CallTerminationReason
import com.botglobal.mobile.platform.calling.OutgoingCallRequest
import com.botglobal.mobile.platform.calling.UnavailableCallPlatformLifecycle
import com.botglobal.mobile.platform.calling.UnavailableCallingDirectory
import com.botglobal.mobile.platform.contacts.ContactsController
import com.botglobal.mobile.platform.contacts.UnavailableContactsGateway
import com.botglobal.mobile.platform.device.UnavailablePermissionController
import com.botglobal.mobile.platform.device.PermissionController
import com.botglobal.mobile.platform.device.PermissionKind
import com.botglobal.mobile.platform.device.PermissionState
import com.botglobal.mobile.platform.identity.FederatedAuthenticationState
import com.botglobal.mobile.platform.identity.FederatedIdentityController
import com.botglobal.mobile.platform.identity.FederatedIdentityProvider
import com.botglobal.mobile.platform.identity.UnavailableFederatedCredentialProvider
import com.botglobal.mobile.platform.identity.UnavailableFederatedIdentityGateway
import com.botglobal.mobile.platform.localization.LocaleController
import com.botglobal.mobile.platform.navigation.BackStackNavigator
import com.botglobal.mobile.platform.notifications.PushRegistrationLifecycle
import com.botglobal.mobile.platform.notifications.UnavailablePushRegistrationLifecycle
import com.botglobal.mobile.platform.voice.ManagedVoiceRoomController
import com.botglobal.mobile.platform.voice.VoiceIceConfiguration
import com.botglobal.mobile.platform.voice.VoiceJoinResult
import com.botglobal.mobile.platform.voice.VoiceMediaPeerFactory
import com.botglobal.mobile.platform.voice.VoiceSignalingTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class NqrbDestination {
    SignIn,
    ContactsOnboarding,
    Home,
    History,
    People,
    Profile,
    Settings,
}

enum class NqrbStartupState { RestoringSession, Ready }

class NqrbAppState(
    val identity: FederatedIdentityController = FederatedIdentityController(
        UnavailableFederatedCredentialProvider,
        UnavailableFederatedIdentityGateway,
    ),
    val contacts: ContactsController = ContactsController(
        UnavailablePermissionController,
        UnavailableContactsGateway,
    ),
    val locale: LocaleController = LocaleController(DEFAULT_LANGUAGE),
    val appearance: AppearanceController = AppearanceController(),
    val navigation: BackStackNavigator<NqrbDestination> = BackStackNavigator(NqrbDestination.SignIn),
    val calling: CallSessionController = unavailableCalling(),
    val callingDirectory: CallingDirectoryController = CallingDirectoryController(
        UnavailableCallingDirectory,
    ),
    private val push: PushRegistrationLifecycle = UnavailablePushRegistrationLifecycle,
    private val permissions: PermissionController = UnavailablePermissionController,
    private val callActionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val startupMutex = Mutex()
    private var startupCompleted = false
    private val mutableStartupState = MutableStateFlow(NqrbStartupState.RestoringSession)
    val startupState = mutableStartupState.asStateFlow()
    val microphoneExplanationVisible = MutableStateFlow(false)
    val microphonePermissionBlocked = MutableStateFlow(false)
    private var pendingMicrophoneAction = PendingMicrophoneAction.Outgoing
    private var pendingOutgoingParticipant: CallableParticipant? = null

    suspend fun startup() = startupMutex.withLock {
        if (startupCompleted) return@withLock
        mutableStartupState.value = NqrbStartupState.RestoringSession
        try {
            identity.restore()
            navigation.reset(
                if (identity.state.value is FederatedAuthenticationState.SignedIn) {
                    runCatching { push.activate() }
                    runCatching { calling.connectSignaling() }
                    refreshCallingDirectory()
                    NqrbDestination.Home
                } else {
                    NqrbDestination.SignIn
                },
            )
        } finally {
            startupCompleted = true
            mutableStartupState.value = NqrbStartupState.Ready
        }
    }

    suspend fun signInWithGoogle() {
        identity.signIn(FederatedIdentityProvider.Google)
        if (identity.state.value is FederatedAuthenticationState.SignedIn) {
            runCatching { push.activate() }
            runCatching { calling.connectSignaling() }
            refreshCallingDirectory()
            navigation.reset(NqrbDestination.ContactsOnboarding)
        }
    }

    suspend fun allowContacts() {
        contacts.requestAndLoad()
        navigation.reset(NqrbDestination.Home)
    }

    fun skipContacts() {
        navigation.reset(NqrbDestination.Home)
    }

    suspend fun refreshContacts() {
        contacts.refresh()
    }

    suspend fun requestContactsFromPeople() {
        contacts.requestAndLoad()
    }

    suspend fun logout() {
        runCatching { calling.disconnectSignaling() }
        runCatching { push.deactivate() }
        identity.logout()
        callingDirectory.clear()
        navigation.reset(NqrbDestination.SignIn)
    }

    fun openSettings() = navigation.push(NqrbDestination.Settings)

    fun selectTopLevel(destination: NqrbDestination): Boolean {
        require(destination in TOP_LEVEL_DESTINATIONS) { "Destination is not a top-level NQRB destination." }
        if (identity.state.value !is FederatedAuthenticationState.SignedIn) return false
        navigation.selectTopLevel(destination)
        if (destination == NqrbDestination.Home) refreshCallingDirectory()
        return true
    }

    fun canUseHome(): Boolean = identity.state.value is FederatedAuthenticationState.SignedIn

    fun refreshCallingDirectory() {
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn
            ?: return
        callActionScope.launch {
            callingDirectory.refresh(signedIn.session.identity.membershipId)
        }
    }

    fun requestOutgoingCall(participant: CallableParticipant) {
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn
            ?: return
        if (participant.membershipId == signedIn.session.identity.membershipId) return
        pendingOutgoingParticipant = participant
        callActionScope.launch { requestOutgoingCallInternal(participant) }
    }

    private suspend fun requestOutgoingCallInternal(participant: CallableParticipant) {
        pendingMicrophoneAction = PendingMicrophoneAction.Outgoing
        microphonePermissionBlocked.value = false
        when (permissions.state(PermissionKind.Microphone)) {
            PermissionState.Granted -> startSelectedCall(participant)
            PermissionState.Unknown, PermissionState.Denied -> microphoneExplanationVisible.value = true
            PermissionState.PermanentlyDenied, PermissionState.Unavailable -> microphonePermissionBlocked.value = true
        }
    }

    fun requestAcceptIncomingCall() {
        callActionScope.launch { requestAcceptIncomingCallInternal() }
    }

    private suspend fun requestAcceptIncomingCallInternal() {
        pendingMicrophoneAction = PendingMicrophoneAction.Incoming
        pendingOutgoingParticipant = null
        microphonePermissionBlocked.value = false
        when (permissions.state(PermissionKind.Microphone)) {
            PermissionState.Granted -> calling.acceptIncoming()
            PermissionState.Unknown, PermissionState.Denied -> microphoneExplanationVisible.value = true
            PermissionState.PermanentlyDenied, PermissionState.Unavailable -> microphonePermissionBlocked.value = true
        }
    }

    fun continueAfterMicrophoneExplanation() {
        callActionScope.launch {
            microphoneExplanationVisible.value = false
            if (permissions.requestAfterExplanation(PermissionKind.Microphone) == PermissionState.Granted) {
                when (pendingMicrophoneAction) {
                    PendingMicrophoneAction.Outgoing -> pendingOutgoingParticipant?.let {
                        startSelectedCall(it)
                    }
                    PendingMicrophoneAction.Incoming -> calling.acceptIncoming()
                }
            } else {
                microphonePermissionBlocked.value = true
            }
        }
    }

    fun setCallMuted(muted: Boolean) {
        callActionScope.launch { calling.setMuted(muted) }
    }

    fun requestCallRoute(route: CallAudioRoute) {
        callActionScope.launch { calling.requestRoute(route) }
    }

    fun endCall(reason: CallTerminationReason = CallTerminationReason.Local) {
        callActionScope.launch { calling.end(reason) }
    }

    fun rejectIncomingCall() {
        callActionScope.launch { calling.rejectIncoming() }
    }

    fun cancelMicrophoneExplanation() {
        microphoneExplanationVisible.value = false
        pendingOutgoingParticipant = null
    }

    private suspend fun startSelectedCall(participant: CallableParticipant) {
        pendingOutgoingParticipant = null
        calling.start(
            OutgoingCallRequest(
                applicationContext = "nqrb",
                callee = CallParticipant(
                    participant.membershipId,
                    participant.displayName,
                ),
            ),
        )
    }

    companion object {
        const val DEFAULT_LANGUAGE = "ar"
        val TOP_LEVEL_DESTINATIONS = setOf(
            NqrbDestination.Home,
            NqrbDestination.History,
            NqrbDestination.People,
            NqrbDestination.Profile,
        )

        private fun unavailableCalling(): CallSessionController {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val signaling = object : VoiceSignalingTransport {
                override val signals = emptyFlow<com.botglobal.mobile.platform.voice.VoiceSignal>()
                override suspend fun iceConfiguration(roomId: String) = VoiceIceConfiguration(emptyList(), "")
                override suspend fun join(roomId: String, generation: Long) = VoiceJoinResult(roomId, generation, "", false, false)
                override suspend fun leave(roomId: String, generation: Long) = Unit
                override suspend fun offer(roomId: String, generation: Long, sessionDescription: String) = Unit
                override suspend fun answer(roomId: String, generation: Long, sessionDescription: String) = Unit
                override suspend fun iceCandidate(roomId: String, generation: Long, candidate: String, sdpMid: String?, sdpMLineIndex: Int) = Unit
                override suspend fun muted(roomId: String, generation: Long, muted: Boolean) = Unit
            }
            val room = ManagedVoiceRoomController(scope, signaling, VoiceMediaPeerFactory { _, _, _ -> error("Calling unavailable") })
            return CallSessionController(
                scope,
                object : com.botglobal.mobile.platform.calling.CallSignaling {
                    override suspend fun startOutgoing(request: OutgoingCallRequest) = error("Calling unavailable")
                    override suspend fun end(callId: com.botglobal.mobile.platform.calling.CallId, reason: com.botglobal.mobile.platform.calling.CallTerminationReason) = Unit
                },
                room,
                UnavailableCallPlatformLifecycle,
            )
        }
    }

    private enum class PendingMicrophoneAction { Outgoing, Incoming }
}
