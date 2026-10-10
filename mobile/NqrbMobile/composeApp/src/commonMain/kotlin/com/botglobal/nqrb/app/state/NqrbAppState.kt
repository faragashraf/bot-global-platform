package com.botglobal.nqrb.app.state

import com.botglobal.mobile.platform.appearance.AppearanceController
import com.botglobal.mobile.platform.calling.CallParticipant
import com.botglobal.mobile.platform.calling.CallId
import com.botglobal.mobile.platform.calling.CallAudioRoute
import com.botglobal.mobile.platform.calling.CallDirection
import com.botglobal.mobile.platform.calling.CallSessionSnapshot
import com.botglobal.mobile.platform.calling.CallState
import com.botglobal.mobile.platform.calling.CallableParticipant
import com.botglobal.mobile.platform.calling.CallingDirectoryController
import com.botglobal.mobile.platform.calling.CallingDirectorySnapshot
import com.botglobal.mobile.platform.calling.CallingDirectoryStatus
import com.botglobal.mobile.platform.calling.CallingParticipantAvailability
import com.botglobal.mobile.platform.calling.CallSessionController
import com.botglobal.mobile.platform.calling.CallTerminationReason
import com.botglobal.mobile.platform.calling.CallActivityController
import com.botglobal.mobile.platform.calling.CallHistoryFilter
import com.botglobal.mobile.platform.calling.FinalCallUsage
import com.botglobal.mobile.platform.calling.UnavailableCallActivityGateway
import com.botglobal.mobile.platform.calling.OutgoingCallRequest
import com.botglobal.mobile.platform.calling.UnavailableCallPlatformLifecycle
import com.botglobal.mobile.platform.calling.UnavailableCallingDirectory
import com.botglobal.mobile.platform.device.UnavailablePermissionController
import com.botglobal.mobile.platform.device.PermissionController
import com.botglobal.mobile.platform.device.PermissionKind
import com.botglobal.mobile.platform.device.PermissionState
import com.botglobal.mobile.platform.identity.FederatedAuthenticationState
import com.botglobal.mobile.platform.identity.FederatedIdentityController
import com.botglobal.mobile.platform.identity.FederatedIdentityProvider
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.identity.UnavailableFederatedCredentialProvider
import com.botglobal.mobile.platform.identity.UnavailableFederatedIdentityGateway
import com.botglobal.mobile.platform.localization.LocaleController
import com.botglobal.mobile.platform.navigation.BackStackNavigator
import com.botglobal.mobile.platform.notifications.PushRegistrationLifecycle
import com.botglobal.mobile.platform.notifications.UnavailablePushRegistrationLifecycle
import com.botglobal.mobile.platform.notifications.PushRegistrationOutcome
import com.botglobal.mobile.platform.notifications.InMemoryNotificationInbox
import com.botglobal.mobile.platform.notifications.NotificationInbox
import com.botglobal.mobile.platform.update.UpdateMode
import com.botglobal.mobile.platform.update.UpdatePolicyEngine
import com.botglobal.mobile.platform.reviews.ReviewCoordinator
import com.botglobal.mobile.platform.reviews.ReviewAttemptResult
import com.botglobal.mobile.platform.reviews.ReviewTrigger
import com.botglobal.nqrb.app.data.NqrbAccountDeletionGateway
import com.botglobal.nqrb.app.data.NqrbAccountDeletionOutcome
import com.botglobal.nqrb.app.data.UnavailableNqrbAccountDeletionGateway
import com.botglobal.nqrb.app.data.NqrbAccountProfile
import com.botglobal.nqrb.app.data.NqrbAccountProfileGateway
import com.botglobal.nqrb.app.data.NqrbAccountProfileResult
import com.botglobal.nqrb.app.data.UnavailableNqrbAccountProfileGateway
import com.botglobal.nqrb.app.data.AllowCurrentNqrbUpdatePolicyGateway
import com.botglobal.nqrb.app.data.NqrbUpdatePolicyGateway
import com.botglobal.mobile.platform.voice.ManagedVoiceRoomController
import com.botglobal.mobile.platform.voice.VoiceIceConfiguration
import com.botglobal.mobile.platform.voice.VoiceJoinResult
import com.botglobal.mobile.platform.voice.VoiceMediaPeerFactory
import com.botglobal.mobile.platform.voice.VoiceSignalingTransport
import com.botglobal.mobile.platform.chat.ChatController
import com.botglobal.mobile.platform.chat.ChatConversation
import com.botglobal.mobile.platform.chat.ChatSnapshot
import com.botglobal.mobile.platform.chat.ChatVoiceDraft
import com.botglobal.mobile.platform.chat.ChatVoicePlayer
import com.botglobal.mobile.platform.chat.ChatVoiceRecorder
import com.botglobal.mobile.platform.chat.ChatMessage
import com.botglobal.mobile.platform.chat.UnavailableChatVoicePlayer
import com.botglobal.mobile.platform.chat.UnavailableChatVoiceRecorder
import com.botglobal.mobile.platform.preferences.InMemoryPreferenceStore
import com.botglobal.mobile.platform.preferences.PreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.collectLatest
import com.botglobal.nqrb.app.data.NqrbSessionAvailability
import com.botglobal.nqrb.app.data.NqrbSessionAvailabilitySource
import com.botglobal.nqrb.app.data.chatIdentityKey
import com.botglobal.mobile.platform.chat.ChatPlaybackPhase
import com.botglobal.mobile.platform.chat.PendingChatVoice
import com.botglobal.mobile.platform.chat.ChatVoiceFailure
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

enum class NqrbDestination {
    SignIn,
    RequiredUpdate,
    Home,
    History,
    Notifications,
    People,
    Profile,
    Settings,
    Chats,
    ChatThread,
}

enum class NqrbChatRecordingState { Idle, NeedsPermission, NeedsStorageDisclosure, Recording, Finalizing, Sending, Preview, AutoStoppedPreview, Unavailable, ActiveCall, CallInterrupted, BackgroundInterrupted, AudioInterrupted, PermissionDenied, PermissionPermanentlyDenied, PlaybackUnavailable, StorageUnavailable }

sealed interface NqrbDirectChatEntryState {
    data object Idle : NqrbDirectChatEntryState
    data class Opening(val membershipReference: String) : NqrbDirectChatEntryState
    data class Failed(val membershipReference: String) : NqrbDirectChatEntryState
}

enum class NqrbChatCallUnavailableReason { SignedOut, StaleAccount, Unknown, Self, Blocked, Offline }

data class NqrbChatCallTarget(
    val participant: CallableParticipant? = null,
    val unavailableReason: NqrbChatCallUnavailableReason? = null,
) {
    val enabled: Boolean get() = participant != null && unavailableReason == null
}

internal fun resolveNqrbChatCallTarget(
    conversation: ChatConversation?,
    currentMembershipId: String?,
    hasCurrentChatAccount: Boolean,
    directory: CallingDirectorySnapshot,
    blockedMembershipIds: Set<String>,
    allowServerPrecheckedAttempt: Boolean = false,
): NqrbChatCallTarget {
    if (currentMembershipId.isNullOrBlank()) return NqrbChatCallTarget(unavailableReason = NqrbChatCallUnavailableReason.SignedOut)
    if (!hasCurrentChatAccount) return NqrbChatCallTarget(unavailableReason = NqrbChatCallUnavailableReason.StaleAccount)
    val reference = conversation?.counterpartReference?.takeIf(String::isNotBlank)
        ?: return NqrbChatCallTarget(unavailableReason = NqrbChatCallUnavailableReason.Unknown)
    if (reference == currentMembershipId) return NqrbChatCallTarget(unavailableReason = NqrbChatCallUnavailableReason.Self)
    if (reference in blockedMembershipIds) return NqrbChatCallTarget(unavailableReason = NqrbChatCallUnavailableReason.Blocked)
    if (directory.status != CallingDirectoryStatus.Ready) {
        return NqrbChatCallTarget(unavailableReason = NqrbChatCallUnavailableReason.Unknown)
    }
    val participant = directory.participants.singleOrNull { it.membershipId == reference }
        ?: return NqrbChatCallTarget(unavailableReason = NqrbChatCallUnavailableReason.Unknown)
    if (!allowServerPrecheckedAttempt && participant.availability == CallingParticipantAvailability.Offline) {
        return NqrbChatCallTarget(unavailableReason = NqrbChatCallUnavailableReason.Offline)
    }
    return NqrbChatCallTarget(participant = participant)
}

enum class NqrbStartupState { RestoringSession, Ready }

enum class NqrbAccountActionState {
    Idle,
    SigningOut,
    Deleting,
    SignOutFailed,
    DeletionFailed,
}

sealed interface NqrbAccountProfileState {
    data object Hidden : NqrbAccountProfileState
    data object Loading : NqrbAccountProfileState
    data class Available(val profile: NqrbAccountProfile) : NqrbAccountProfileState
    data object Failed : NqrbAccountProfileState
}

fun interface NqrbLocalAccountDataCleaner {
    suspend fun clear()
}

private object UnavailableNqrbLocalAccountDataCleaner : NqrbLocalAccountDataCleaner {
    override suspend fun clear() = Unit
}

class NqrbAppState(
    val identity: FederatedIdentityController = FederatedIdentityController(
        UnavailableFederatedCredentialProvider,
        UnavailableFederatedIdentityGateway,
    ),
    val locale: LocaleController = LocaleController(DEFAULT_LANGUAGE),
    val appearance: AppearanceController = AppearanceController(),
    val ringtone: NqrbRingtoneSettings = NqrbRingtoneSettings(),
    val navigation: BackStackNavigator<NqrbDestination> = BackStackNavigator(NqrbDestination.SignIn),
    val calling: CallSessionController = unavailableCalling(),
    val callingDirectory: CallingDirectoryController = CallingDirectoryController(
        UnavailableCallingDirectory,
    ),
    val contactBook: NqrbContactBookController = NqrbContactBookController(),
    val callActivity: CallActivityController = CallActivityController(UnavailableCallActivityGateway),
    val chat: ChatController = ChatController(),
    val chatVoiceRecorder: ChatVoiceRecorder = UnavailableChatVoiceRecorder,
    val chatVoicePlayer: ChatVoicePlayer = UnavailableChatVoicePlayer,
    val notificationInbox: NotificationInbox = InMemoryNotificationInbox(),
    private val push: PushRegistrationLifecycle = UnavailablePushRegistrationLifecycle,
    private val accountDeletion: NqrbAccountDeletionGateway = UnavailableNqrbAccountDeletionGateway,
    private val accountProfile: NqrbAccountProfileGateway = UnavailableNqrbAccountProfileGateway,
    private val sessionAvailability: NqrbSessionAvailabilitySource? = accountProfile as? NqrbSessionAvailabilitySource,
    private val localAccountDataCleaner: NqrbLocalAccountDataCleaner = UnavailableNqrbLocalAccountDataCleaner,
    private val permissions: PermissionController = UnavailablePermissionController,
    private val openMicrophoneSettings: () -> Unit = {},
    private val reviews: ReviewCoordinator? = null,
    private val updatePolicy: NqrbUpdatePolicyGateway = AllowCurrentNqrbUpdatePolicyGateway,
    private val chatPreferences: PreferenceStore = InMemoryPreferenceStore(),
    private val currentVersion: String = "0.0.0",
    private val platform: String = "android",
    private val callActionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val appVersion: String = currentVersion

    private val startupMutex = Mutex()
    private var startupCompleted = false
    private val mutableStartupState = MutableStateFlow(NqrbStartupState.RestoringSession)
    val startupState = mutableStartupState.asStateFlow()
    val microphoneExplanationVisible = MutableStateFlow(false)
    val microphonePermissionBlocked = MutableStateFlow(false)
    private val mutableShowCurrentCallRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val showCurrentCallRequests = mutableShowCurrentCallRequests.asSharedFlow()
    private val mutableAccountActionState = MutableStateFlow(NqrbAccountActionState.Idle)
    val accountActionState = mutableAccountActionState.asStateFlow()
    private val mutableAccountProfileState = MutableStateFlow<NqrbAccountProfileState>(NqrbAccountProfileState.Hidden)
    val accountProfileState = mutableAccountProfileState.asStateFlow()
    private val mutableRequiredUpdateMessage = MutableStateFlow<String?>(null)
    val requiredUpdateMessage = mutableRequiredUpdateMessage.asStateFlow()
    private val mutableRequiredUpdateDestination = MutableStateFlow<String?>(null)
    val requiredUpdateDestination = mutableRequiredUpdateDestination.asStateFlow()
    private val accountProfileLifecycleMutex = Mutex()
    private var accountProfileRequestGeneration = 0L
    private var pendingMicrophoneAction = PendingMicrophoneAction.Outgoing
    private var pendingOutgoingParticipant: CallableParticipant? = null
    private var pendingInviteCode: String? = null
    private val submittedUsageCalls = mutableSetOf<String>()
    private val requestedCallContacts = mutableSetOf<String>()
    private val pendingHistoryContactAdds = mutableSetOf<String>()
    private val mutableAddedHistoryContactCalls = MutableStateFlow<Set<String>>(emptySet())
    val addedHistoryContactCalls = mutableAddedHistoryContactCalls.asStateFlow()
    val notifications = notificationInbox.notifications
    private val acknowledgedMissedCallIds = mutableSetOf<String>()
    private val transientMissedCallIds = mutableSetOf<String>()
    private val mutableMissedCallBadgeCount = MutableStateFlow(0)
    val missedCallBadgeCount = mutableMissedCallBadgeCount.asStateFlow()
    private val sessionRenewalMutex = Mutex()
    private var foregroundRefreshJob: Job? = null
    private var foreground = false
    private var pendingReviewTrigger: ReviewTrigger? = null
    private val reviewWorkflowBlockers = MutableStateFlow<Set<String>>(emptySet())
    private val mutableChatRecordingState = MutableStateFlow(NqrbChatRecordingState.Idle)
    val chatRecordingState = mutableChatRecordingState.asStateFlow()
    private val mutableChatVoiceDraft = MutableStateFlow<ChatVoiceDraft?>(null)
    val chatVoiceDraft = mutableChatVoiceDraft.asStateFlow()
    private var visibleChatRead: Pair<String, Long>? = null
    private val chatBindingMutex = Mutex()
    private var appliedAvailability: NqrbSessionAvailability? = null
    private val mutableChatSubmitting = MutableStateFlow(false)
    val chatSubmitting = mutableChatSubmitting.asStateFlow()
    private val mutableChatLocalOnly = MutableStateFlow(false)
    val chatLocalOnly = mutableChatLocalOnly.asStateFlow()
    private val mutableDirectChatEntryState = MutableStateFlow<NqrbDirectChatEntryState>(NqrbDirectChatEntryState.Idle)
    val directChatEntryState = mutableDirectChatEntryState.asStateFlow()
    private var directChatRequestGeneration = 0L
    private var directChatEntryJob: Job? = null
    private var submittingDraft: ChatVoiceDraft? = null
    private var abandonedSubmission = false
    private data class PlaybackIntent(val generation: Long = 0, val key: String? = null, val job: Job? = null)
    private val chatPlaybackIntent = MutableStateFlow(PlaybackIntent())
    private val chatPlaybackSuspended = MutableStateFlow(false)

    private fun invalidateChatPlayback(): Long {
        while (true) {
            val previous = chatPlaybackIntent.value
            if (chatPlaybackIntent.compareAndSet(previous, PlaybackIntent(previous.generation + 1))) {
                previous.job?.cancel()
                return previous.generation + 1
            }
        }
    }

    private fun invalidateDirectChatEntry() {
        directChatRequestGeneration += 1
        directChatEntryJob?.cancel()
        directChatEntryJob = null
        mutableDirectChatEntryState.value = NqrbDirectChatEntryState.Idle
    }

    private fun launchChatPlayback(key: String, conversationId: String, play: suspend (Long) -> Unit) {
        if (chatPlaybackSuspended.value || calling.state.value.state in OngoingCallStates ||
            navigation.current != NqrbDestination.ChatThread || chat.state.value.selectedConversationId != conversationId) return
        val binding = chat.state.value.accountGeneration
        while (true) {
            val previous = chatPlaybackIntent.value
            if (previous.job?.isCompleted == false && previous.key == key) return
            val intent = previous.generation + 1
            val job = callActionScope.launch(start = CoroutineStart.LAZY) {
                if (intent == chatPlaybackIntent.value.generation && binding == chat.state.value.accountGeneration &&
                    !chatPlaybackSuspended.value && calling.state.value.state !in OngoingCallStates &&
                    navigation.current == NqrbDestination.ChatThread && chat.state.value.selectedConversationId == conversationId) play(intent)
            }
            if (chatPlaybackIntent.compareAndSet(previous, PlaybackIntent(intent, key, job))) {
                previous.job?.cancel(); job.start(); return
            }
            job.cancel()
        }
    }

    private fun localChatAvailable(): Boolean = identity.state.value is FederatedAuthenticationState.SignedIn ||
        sessionAvailability?.availability?.value is NqrbSessionAvailability.CachedLocal ||
        sessionAvailability?.availability?.value is NqrbSessionAvailability.Online

    private suspend fun applyChatAvailability(value: NqrbSessionAvailability) = chatBindingMutex.withLock {
        if (sessionAvailability?.availability?.value != value || appliedAvailability == value) return@withLock
        stopChatPlayback()
        mutableChatLocalOnly.value = value is NqrbSessionAvailability.CachedLocal
        val selected = chat.state.value.selectedConversationId
        val previousAccount = chat.state.value.account
        when (value) {
            NqrbSessionAvailability.Unavailable -> {
                invalidateDirectChatEntry()
                chat.bind(null)
                cancelChatRecording()
                if (startupCompleted) navigation.reset(NqrbDestination.SignIn)
            }
            is NqrbSessionAvailability.CachedLocal -> chat.bindLocal(value.session.chatIdentityKey())
            is NqrbSessionAvailability.Online -> chat.bindAuthenticated(resumeNetwork = false)
        }
        if (sessionAvailability?.availability?.value != value) return@withLock
        if (previousAccount != null && previousAccount == chat.state.value.account) chat.selectConversation(selected)
        appliedAvailability = value
    }

    private suspend fun renewChatAuthority(force: Boolean = false): Boolean {
        val source = sessionAvailability ?: return identity.state.value is FederatedAuthenticationState.SignedIn
        val before = source.availability.value
        val online = (before as? NqrbSessionAvailability.Online)?.session
        if (force || online == null || online.accessExpiresSoon() || chat.state.value.retryableFailure) runCatching { source.refreshAuthority() }
        val value = source.availability.value
        applyChatAvailability(value)
        return value is NqrbSessionAvailability.Online && source.availability.value == value
    }

    init {
        sessionAvailability?.let { source -> callActionScope.launch {
            source.availability.collectLatest { value ->
                applyChatAvailability(value)
                if (value is NqrbSessionAvailability.Online && source.availability.value == value) {
                    runCatching { calling.connectSignaling() }
                    runCatching { chat.resumeOnline() }
                } else if (value is NqrbSessionAvailability.Unavailable) {
                    calling.clearForAccountChange()
                    runCatching { calling.disconnectSignaling() }
                }
            }
        } }
        chatVoiceRecorder.onAutomaticStop { draft ->
            if (mutableChatRecordingState.value !in setOf(NqrbChatRecordingState.Recording, NqrbChatRecordingState.Finalizing)) {
                if (draft != null) callActionScope.launch { chatVoiceRecorder.discard(draft) }
                return@onAutomaticStop
            }
            if (draft != null && (draft.owner != chat.state.value.account || draft.conversationId != chat.state.value.selectedConversationId)) {
                callActionScope.launch { chatVoiceRecorder.discard(draft) }
                return@onAutomaticStop
            }
            mutableChatVoiceDraft.value = draft
            mutableChatRecordingState.value = if (draft == null) NqrbChatRecordingState.AudioInterrupted else NqrbChatRecordingState.AutoStoppedPreview
        }
        callActionScope.launch {
            calling.state.collect { snapshot ->
                if (snapshot.state in OngoingCallStates) { invalidateChatPlayback(); chatVoicePlayer.stop() }
                if (snapshot.state in OngoingCallStates && mutableChatRecordingState.value == NqrbChatRecordingState.Recording) {
                    chatVoiceRecorder.cancel()
                    mutableChatRecordingState.value = NqrbChatRecordingState.CallInterrupted
                }
                val callId = snapshot.callId?.value ?: return@collect
                queueIncomingContactLookup(snapshot)
                if (snapshot.state == CallState.Missed) markMissedCall(callId)
                val usage = snapshot.networkUsage
                if (usage.isFinal && usage.measurementAvailable) {
                    val membershipId = (identity.state.value as? FederatedAuthenticationState.SignedIn)
                        ?.session?.identity?.membershipId ?: return@collect
                    if (!submittedUsageCalls.add(callId)) return@collect
                    callActivity.submit(FinalCallUsage(callId, usage.bytesSent, usage.bytesReceived,
                        usage.connectedDurationSeconds ?: 0), membershipId)
                    if ((usage.connectedDurationSeconds ?: 0) >= NqrbMeaningfulCallDurationSeconds) {
                        pendingReviewTrigger = ReviewTrigger.CompletedExperience
                        reviews?.recordMeaningfulEvent("nqrb:call:$callId")
                        requestPendingReviewIfReady()
                    }
                }
            }
        }
        callActionScope.launch {
            callActivity.state.collect { snapshot ->
                refreshMissedCallBadgeFromHistory(snapshot.history)
            }
        }
    }

    suspend fun startup() = startupMutex.withLock {
        if (startupCompleted) return@withLock
        mutableStartupState.value = NqrbStartupState.RestoringSession
        try {
            if (requiresUpdate()) {
                navigation.reset(NqrbDestination.RequiredUpdate)
                return@withLock
            }
            invalidateAccountProfileRequests(hideProfile = true)
            identity.restore()
            val authenticated = identity.state.value as? FederatedAuthenticationState.SignedIn
            navigation.reset(
                if (authenticated != null) {
                    runCatching { push.activate() }
                    runCatching { calling.connectSignaling() }
                    runCatching { if (sessionAvailability == null) chat.bindAuthenticated() else applyChatAvailability(sessionAvailability.availability.value) }
                    contactBook.load(authenticated.session)
                    queueIncomingContactLookup(calling.state.value)
                    refreshCallingDirectory()
                    callActivity.flushPending(authenticated.session.identity.membershipId)
                    if (processPendingInvite(authenticated.session)) {
                        NqrbDestination.People
                    } else {
                        NqrbDestination.Home
                    }
                } else if (sessionAvailability?.availability?.value is NqrbSessionAvailability.CachedLocal) {
                    applyChatAvailability(sessionAvailability.availability.value)
                    NqrbDestination.Chats
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
        invalidateDirectChatEntry()
        invalidateAccountProfileRequests(hideProfile = true)
        identity.signIn(FederatedIdentityProvider.Google)
        val authenticated = identity.state.value as? FederatedAuthenticationState.SignedIn
        if (authenticated != null) {
            invalidateAccountProfileRequests(hideProfile = true)
            runCatching { push.activate() }
            runCatching { calling.connectSignaling() }
            runCatching { if (sessionAvailability == null) chat.bindAuthenticated() else applyChatAvailability(sessionAvailability.availability.value) }
            contactBook.load(authenticated.session)
            queueIncomingContactLookup(calling.state.value)
            refreshCallingDirectory()
            callActivity.flushPending(authenticated.session.identity.membershipId)
            navigation.reset(
                if (processPendingInvite(authenticated.session)) {
                    NqrbDestination.People
                } else {
                    NqrbDestination.Home
                },
            )
        }
    }

    suspend fun logout() {
        if (mutableAccountActionState.value in setOf(
                NqrbAccountActionState.SigningOut,
                NqrbAccountActionState.Deleting,
            )
        ) return
        invalidateDirectChatEntry()
        val profileInvalidation = invalidateAccountProfileRequests(hideProfile = false)
        mutableAccountActionState.value = NqrbAccountActionState.SigningOut
        val unpair = try {
            if (sessionAvailability?.availability?.value is NqrbSessionAvailability.CachedLocal)
                PushRegistrationOutcome.Unregistered else push.deactivate()
        } catch (cancelled: CancellationException) {
            mutableAccountActionState.value = NqrbAccountActionState.Idle
            throw cancelled
        } catch (_: Exception) {
            PushRegistrationOutcome.RetryableFailure
        }
        if (unpair != PushRegistrationOutcome.Unregistered) {
            markInvalidatedLoadingProfileAsFailed(profileInvalidation)
            mutableAccountActionState.value = NqrbAccountActionState.SignOutFailed
            return
        }
        runCatching { calling.disconnectSignaling() }
        invalidateChatPlayback()
        runCatching { chat.bind(null) }
        runCatching { chatVoiceRecorder.cancel() }
        abandonedSubmission = true
        mutableChatVoiceDraft.value?.takeUnless { it == submittingDraft }?.let { draft -> runCatching { chatVoiceRecorder.discard(draft) } }
        runCatching { chatVoicePlayer.stop() }
        mutableChatVoiceDraft.value = null
        mutableChatRecordingState.value = NqrbChatRecordingState.Idle
        identity.logout()
        mutableAccountProfileState.value = NqrbAccountProfileState.Hidden
        callingDirectory.clear()
        contactBook.clear()
        pendingInviteCode = null
        callActivity.clear()
        submittedUsageCalls.clear()
        acknowledgedMissedCallIds.clear()
        transientMissedCallIds.clear()
        mutableMissedCallBadgeCount.value = 0
        pendingHistoryContactAdds.clear()
        reviewWorkflowBlockers.value = emptySet()
        mutableAddedHistoryContactCalls.value = emptySet()
        navigation.reset(NqrbDestination.SignIn)
        mutableAccountActionState.value = NqrbAccountActionState.Idle
    }

    suspend fun deleteAccount() {
        if (mutableAccountActionState.value in setOf(
                NqrbAccountActionState.SigningOut,
                NqrbAccountActionState.Deleting,
            )
        ) return
        val profileInvalidation = invalidateAccountProfileRequests(hideProfile = false)
        mutableAccountActionState.value = NqrbAccountActionState.Deleting
        val outcome = try {
            accountDeletion.deleteCurrentAccount()
        } catch (cancelled: CancellationException) {
            mutableAccountActionState.value = NqrbAccountActionState.Idle
            throw cancelled
        } catch (_: Exception) {
            NqrbAccountDeletionOutcome.RetryableFailure
        }
        when (outcome) {
            NqrbAccountDeletionOutcome.Deleted,
            NqrbAccountDeletionOutcome.Accepted,
            -> completeLocalAccountDeletion()
            NqrbAccountDeletionOutcome.AuthenticationRequired,
            NqrbAccountDeletionOutcome.RetryableFailure,
            NqrbAccountDeletionOutcome.Rejected,
            -> {
                markInvalidatedLoadingProfileAsFailed(profileInvalidation)
                mutableAccountActionState.value = NqrbAccountActionState.DeletionFailed
            }
        }
    }

    fun dismissAccountActionError() {
        if (mutableAccountActionState.value in setOf(
                NqrbAccountActionState.SignOutFailed,
                NqrbAccountActionState.DeletionFailed,
            )
        ) {
            mutableAccountActionState.value = NqrbAccountActionState.Idle
        }
    }

    private suspend fun completeLocalAccountDeletion() {
        invalidateDirectChatEntry()
        val unsentPreview = mutableChatVoiceDraft.value
        invalidateChatPlayback()
        chat.state.value.account?.let { runCatching { chat.deleteAccountData(it) } }
        runCatching { calling.disconnectSignaling() }
        runCatching { chat.bind(null) }
        if (unsentPreview != null) runCatching { chatVoiceRecorder.discard(unsentPreview) }
        runCatching { chatVoiceRecorder.cancel() }
        runCatching { chatVoicePlayer.stop() }
        mutableChatVoiceDraft.value = null
        mutableChatRecordingState.value = NqrbChatRecordingState.Idle
        runCatching { push.clearLocalState() }
        runCatching { localAccountDataCleaner.clear() }
        identity.logout()
        mutableAccountProfileState.value = NqrbAccountProfileState.Hidden
        callingDirectory.clear()
        contactBook.clear()
        pendingInviteCode = null
        callActivity.clear()
        submittedUsageCalls.clear()
        acknowledgedMissedCallIds.clear()
        transientMissedCallIds.clear()
        mutableMissedCallBadgeCount.value = 0
        pendingHistoryContactAdds.clear()
        reviewWorkflowBlockers.value = emptySet()
        mutableAddedHistoryContactCalls.value = emptySet()
        navigation.reset(NqrbDestination.SignIn)
        mutableAccountActionState.value = NqrbAccountActionState.Idle
    }

    fun openSettings() {
        invalidateDirectChatEntry()
        navigation.push(NqrbDestination.Settings)
        callActionScope.launch { callActivity.loadUsage() }
    }

    fun openChats() {
        if (!localChatAvailable()) return
        invalidateDirectChatEntry()
        stopChatPlayback()
        navigation.push(NqrbDestination.Chats)
        callActionScope.launch { resumeChatOnline() }
    }

    fun refreshChat() { callActionScope.launch {
        runCatching { resumeChatOnline(force = true) }
            .onFailure { mutableChatRecordingState.value = NqrbChatRecordingState.StorageUnavailable }
    } }

    fun retryChatDelivery() { callActionScope.launch {
        val selected = chat.state.value.selectedConversationId
        runCatching { resumeChatOnline(force = true, conversationId = selected) }
            .onFailure { mutableChatRecordingState.value = NqrbChatRecordingState.StorageUnavailable }
    } }

    private suspend fun resumeChatOnline(force: Boolean = false, conversationId: String? = chat.state.value.selectedConversationId): Boolean {
        if (!renewChatAuthority(force)) return false
        val ready = if (chat.state.value.account == null) chat.bindAuthenticated() else { chat.resumeOnline(); true }
        if (!ready) return false
        if (conversationId != null) chat.selectConversation(conversationId)
        chat.flush()
        if (conversationId == null) chat.sync() else chat.sync(conversationId)
        return true
    }

    fun leaveChat() {
        visibleChatRead = null
        cancelChatRecording()
        callActionScope.launch { chat.selectConversation(null) }
        navigation.navigateBack()
    }

    fun openChat(conversationId: String) {
        if (!localChatAvailable()) return
        stopChatPlayback()
        callActionScope.launch {
            chat.selectConversation(conversationId)
            navigation.push(NqrbDestination.ChatThread)
            launch { resumeChatOnline(conversationId = conversationId) }
        }
    }

    fun openChatWith(reference: String) {
        val normalizedReference = reference.trim()
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn ?: return
        val currentMembershipId = signedIn.session.identity.membershipId
        val knownMembership = contactBook.state.value.contacts.any { it.membershipId == normalizedReference } ||
            callingDirectory.state.value.participants.any { it.membershipId == normalizedReference }
        val blocked = contactBook.state.value.blockedAccounts.any { it.membershipId == normalizedReference }
        if (normalizedReference.isBlank() || normalizedReference == currentMembershipId || blocked || !knownMembership) {
            invalidateDirectChatEntry()
            mutableDirectChatEntryState.value = NqrbDirectChatEntryState.Failed(normalizedReference)
            return
        }
        if ((mutableDirectChatEntryState.value as? NqrbDirectChatEntryState.Opening)?.membershipReference == normalizedReference) return
        val requestOrigin = navigation.current
        invalidateDirectChatEntry()
        stopChatPlayback()
        val requestGeneration = directChatRequestGeneration
        mutableDirectChatEntryState.value = NqrbDirectChatEntryState.Opening(normalizedReference)
        directChatEntryJob = callActionScope.launch {
            try {
                if (!renewChatAuthority()) {
                    failDirectChatEntry(requestGeneration, normalizedReference)
                    return@launch
                }
                val bound = chat.state.value
                val expectedAccount = bound.account
                val expectedAccountGeneration = bound.accountGeneration
                if (expectedAccount == null || !isDirectChatRequestCurrent(
                        requestGeneration,
                        signedIn.session,
                        expectedAccount,
                        expectedAccountGeneration,
                        requestOrigin,
                    )
                ) {
                    failDirectChatEntry(requestGeneration, normalizedReference)
                    return@launch
                }
                val existing = bound.conversations.firstOrNull { it.counterpartReference == normalizedReference }
                val conversation = existing ?: chat.createDirect(normalizedReference)
                if (conversation?.counterpartReference != normalizedReference ||
                    !isDirectChatRequestCurrent(
                        requestGeneration,
                        signedIn.session,
                        expectedAccount,
                        expectedAccountGeneration,
                        requestOrigin,
                    )
                ) {
                    failDirectChatEntry(requestGeneration, normalizedReference)
                    return@launch
                }
                chat.selectConversation(conversation.conversationId)
                if (!isDirectChatRequestCurrent(
                        requestGeneration,
                        signedIn.session,
                        expectedAccount,
                        expectedAccountGeneration,
                        requestOrigin,
                    ) ||
                    chat.state.value.selectedConversationId != conversation.conversationId
                ) {
                    failDirectChatEntry(requestGeneration, normalizedReference)
                    return@launch
                }
                mutableDirectChatEntryState.value = NqrbDirectChatEntryState.Idle
                directChatEntryJob = null
                navigation.push(NqrbDestination.ChatThread)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failDirectChatEntry(requestGeneration, normalizedReference)
            }
        }
    }

    private fun failDirectChatEntry(requestGeneration: Long, reference: String) {
        if (requestGeneration != directChatRequestGeneration) return
        directChatEntryJob = null
        mutableDirectChatEntryState.value = NqrbDirectChatEntryState.Failed(reference)
    }

    private fun isDirectChatRequestCurrent(
        requestGeneration: Long,
        expectedSession: MobileSession,
        expectedAccount: com.botglobal.mobile.platform.chat.ChatAccountScope,
        expectedAccountGeneration: Long,
        expectedOrigin: NqrbDestination,
    ): Boolean {
        val current = chat.state.value
        return requestGeneration == directChatRequestGeneration &&
            isCurrentSession(expectedSession) &&
            current.account == expectedAccount &&
            current.accountGeneration == expectedAccountGeneration &&
            navigation.current == expectedOrigin
    }

    fun handleSystemBack(): Boolean {
        val cancelledDirectChatEntry = mutableDirectChatEntryState.value is NqrbDirectChatEntryState.Opening
        if (cancelledDirectChatEntry) invalidateDirectChatEntry()
        if (!navigation.canNavigateBack) return cancelledDirectChatEntry
        if (navigation.current == NqrbDestination.ChatThread) leaveChat() else navigation.navigateBack()
        return true
    }

    fun resolveChatCallTarget(conversationId: String?): NqrbChatCallTarget {
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn
        val snapshot = chat.state.value
        val conversation = snapshot.conversations.firstOrNull {
            it.conversationId == conversationId && snapshot.selectedConversationId == conversationId
        }
        val selectedAccount = snapshot.account
        val currentAuthority = if (
            signedIn == null ||
            selectedAccount == null ||
            selectedAccount.subjectId.trim() != signedIn.session.identity.subjectId.trim()
        ) false else {
            when (val availability = sessionAvailability?.availability?.value) {
                null -> isCurrentSession(signedIn.session)
                is NqrbSessionAvailability.Online -> availability.session.sameAccountAs(signedIn.session)
                else -> false
            }
        }
        return resolveNqrbChatCallTarget(
            conversation = conversation,
            currentMembershipId = signedIn?.session?.identity?.membershipId,
            hasCurrentChatAccount = currentAuthority,
            directory = callingDirectory.state.value,
            blockedMembershipIds = contactBook.state.value.blockedAccounts.mapTo(mutableSetOf()) { it.membershipId },
            allowServerPrecheckedAttempt = true,
        )
    }

    fun requestChatCall(conversationId: String) {
        resolveChatCallTarget(conversationId).participant?.let(::requestOutgoingCall)
    }

    fun sendChatText(text: String, onQueued: () -> Unit = {}) {
        val conversationId = chat.state.value.selectedConversationId ?: return
        val account = chat.state.value.account ?: return
        if (!mutableChatSubmitting.compareAndSet(false, true)) return
        callActionScope.launch {
            try {
                val sent = runCatching { chat.enqueueText(conversationId, text, account) }.getOrNull()
                if (sent != null) { onQueued(); launch { resumeChatOnline(force = true, conversationId = conversationId) } }
                else mutableChatRecordingState.value = NqrbChatRecordingState.StorageUnavailable
            } finally { mutableChatSubmitting.value = false }
        }
    }

    fun markChatRead(conversationId: String, sequence: Long) {
        visibleChatRead = conversationId to sequence
        val account = chat.state.value.account ?: return
        if (foreground && navigation.current == NqrbDestination.ChatThread && chat.state.value.selectedConversationId == conversationId && sequence > 0)
            callActionScope.launch { runCatching { chat.markRead(conversationId, sequence, account) } }
    }

    fun requestChatVoiceRecording() {
        callActionScope.launch {
            if (mutableChatSubmitting.value) return@launch
            if (calling.state.value.state in OngoingCallStates || !chatVoiceRecorder.available) {
                mutableChatRecordingState.value = if (calling.state.value.state in OngoingCallStates) NqrbChatRecordingState.ActiveCall else NqrbChatRecordingState.Unavailable
                return@launch
            }
            when (permissions.state(PermissionKind.Microphone)) {
                PermissionState.Granted -> continueToChatStorageDisclosure()
                PermissionState.Unknown, PermissionState.Denied -> mutableChatRecordingState.value = NqrbChatRecordingState.NeedsPermission
                PermissionState.PermanentlyDenied -> mutableChatRecordingState.value = NqrbChatRecordingState.PermissionPermanentlyDenied
                PermissionState.Unavailable -> mutableChatRecordingState.value = NqrbChatRecordingState.Unavailable
            }
        }
    }

    fun continueChatVoicePermission() {
        callActionScope.launch {
            when (permissions.requestAfterExplanation(PermissionKind.Microphone)) {
                PermissionState.Granted -> continueToChatStorageDisclosure()
                PermissionState.PermanentlyDenied -> mutableChatRecordingState.value = NqrbChatRecordingState.PermissionPermanentlyDenied
                else -> mutableChatRecordingState.value = NqrbChatRecordingState.PermissionDenied
            }
        }
    }

    private suspend fun continueToChatStorageDisclosure() {
        if (chatPreferences.boolean(ChatStorageDisclosureKey) == true) startChatRecording()
        else mutableChatRecordingState.value = NqrbChatRecordingState.NeedsStorageDisclosure
    }

    fun acceptChatStorageDisclosure() {
        chatPreferences.putBoolean(ChatStorageDisclosureKey, true)
        callActionScope.launch { startChatRecording() }
    }

    private suspend fun startChatRecording() {
        invalidateChatPlayback()
        if (calling.state.value.state in OngoingCallStates) {
            mutableChatRecordingState.value = NqrbChatRecordingState.ActiveCall
            return
        }
        val account = chat.state.value.account ?: return
        val conversation = chat.state.value.selectedConversationId ?: return
        mutableChatVoiceDraft.value?.takeUnless { it == submittingDraft }?.let { chatVoiceRecorder.discard(it) }
        mutableChatVoiceDraft.value = null
        chatVoicePlayer.stop()
        mutableChatRecordingState.value = if (chatVoiceRecorder.start(account, conversation)) NqrbChatRecordingState.Recording else NqrbChatRecordingState.Unavailable
    }

    fun finishChatRecording() {
        if (mutableChatRecordingState.value != NqrbChatRecordingState.Recording) return
        mutableChatRecordingState.value = NqrbChatRecordingState.Finalizing
        callActionScope.launch {
            val draft = chatVoiceRecorder.stop()
            if (mutableChatRecordingState.value != NqrbChatRecordingState.Finalizing) {
                draft?.let { chatVoiceRecorder.discard(it) }; return@launch
            }
            mutableChatVoiceDraft.value = draft
            mutableChatRecordingState.value = if (draft == null) NqrbChatRecordingState.StorageUnavailable else NqrbChatRecordingState.Preview
        }
    }

    fun cancelChatRecording() {
        val playbackIntent = invalidateChatPlayback()
        val draft = mutableChatVoiceDraft.value
        val previewOwned = draft != submittingDraft
        if (submittingDraft != null) abandonedSubmission = true
        mutableChatVoiceDraft.value = null
        mutableChatRecordingState.value = NqrbChatRecordingState.Idle
        callActionScope.launch {
            chatVoiceRecorder.cancel()
            if (draft != null && previewOwned) chatVoiceRecorder.discard(draft)
            if (chatPlaybackIntent.value.generation == playbackIntent) chatVoicePlayer.stop()
        }
    }

    private suspend fun toggleChatPlayback(key: String, play: suspend () -> Boolean) {
        val state = chatVoicePlayer.state.value
        when {
            state.key == key && state.phase == ChatPlaybackPhase.Playing -> chatVoicePlayer.pause()
            state.key == key && state.phase == ChatPlaybackPhase.Paused -> chatVoicePlayer.resume()
            state.key == key && state.phase == ChatPlaybackPhase.Preparing -> Unit
            !play() -> { mutableChatRecordingState.value = NqrbChatRecordingState.PlaybackUnavailable }
        }
    }
    fun previewChatVoice() { mutableChatVoiceDraft.value?.let { draft ->
        val conversation = draft.conversationId ?: return
        launchChatPlayback(draft.token, conversation) { toggleChatPlayback(draft.token) { chatVoicePlayer.playDraft(draft) } }
    } }
    fun stopChatPlayback() {
        val intent = invalidateChatPlayback()
        callActionScope.launch { if (chatPlaybackIntent.value.generation == intent) chatVoicePlayer.stop() }
    }
    fun openChatMicrophoneSettings() { dismissChatVoiceState(); openMicrophoneSettings() }
    fun logoutFromLocalChat() { callActionScope.launch { logout() } }
    fun playPendingChatVoice(pending: PendingChatVoice) {
        val account = chat.state.value.account ?: return
        if (pending.failure == ChatVoiceFailure.MissingOrCorrupt ||
            chat.state.value.pendingVoices.none { it == pending }) return
        val draft = ChatVoiceDraft(pending.draftToken, pending.durationMilliseconds, pending.length,
            owner = account, conversationId = pending.conversationId, sha256 = pending.sha256)
        launchChatPlayback(draft.token, pending.conversationId) { toggleChatPlayback(draft.token) { chatVoicePlayer.playDraft(draft) } }
    }
    fun removeFailedChatVoice(id: String) { callActionScope.launch { chat.removeFailedVoice(id) } }
    fun removeFailedChatText(id: String) { callActionScope.launch { chat.removeFailedText(id) } }
    fun reconcileFailedChatText(id: String) { callActionScope.launch { if (renewChatAuthority()) chat.reconcileFailedText(id) } }
    fun editFailedChatText(id: String, text: String, onQueued: () -> Unit) {
        if (!mutableChatSubmitting.compareAndSet(false, true)) return
        callActionScope.launch {
            try {
                if (runCatching { chat.editFailedText(id, text) }.getOrDefault(false)) {
                    onQueued(); launch { resumeChatOnline(force = true) }
                } else mutableChatRecordingState.value = NqrbChatRecordingState.StorageUnavailable
            } finally { mutableChatSubmitting.value = false }
        }
    }

    fun sendChatVoice() {
        val draft = mutableChatVoiceDraft.value ?: return
        val conversationId = draft.conversationId ?: return
        if (draft.owner != chat.state.value.account || conversationId != chat.state.value.selectedConversationId) return
        if (!mutableChatSubmitting.compareAndSet(false, true)) return
        invalidateChatPlayback()
        submittingDraft = draft
        abandonedSubmission = false
        mutableChatRecordingState.value = NqrbChatRecordingState.Sending
        callActionScope.launch {
            try {
                chatVoicePlayer.stop()
                val accepted = runCatching { chat.enqueueVoice(conversationId, draft) }.getOrNull() != null
                if (accepted) {
                    if (mutableChatVoiceDraft.value == draft) mutableChatVoiceDraft.value = null
                    if (!abandonedSubmission) mutableChatRecordingState.value = NqrbChatRecordingState.Idle
                    launch { resumeChatOnline(force = true, conversationId = conversationId) }
                } else if (abandonedSubmission || draft.owner != chat.state.value.account) {
                    chatVoiceRecorder.discard(draft)
                } else mutableChatRecordingState.value = NqrbChatRecordingState.StorageUnavailable
            } finally { submittingDraft = null; mutableChatSubmitting.value = false }
        }
    }

    fun playChatVoice(message: ChatMessage) {
        val transferId = message.voiceTransferId ?: return
        val scope = chat.state.value.account ?: return
        val binding = chat.state.value.accountGeneration
        launchChatPlayback(transferId, message.conversationId) { intent ->
            val existing = chat.state.value.localVoiceKeys[transferId]
            if (existing == null) chatVoicePlayer.stop()
            val key = existing ?: try { chat.downloadVoice(message)?.localKey }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (key != null && intent == chatPlaybackIntent.value.generation && binding == chat.state.value.accountGeneration &&
                chat.state.value.account == scope && !chatPlaybackSuspended.value && calling.state.value.state !in OngoingCallStates &&
                navigation.current == NqrbDestination.ChatThread && chat.state.value.selectedConversationId == message.conversationId) {
                toggleChatPlayback(key) { chatVoicePlayer.play(scope, key) }
            }
        }
    }

    fun dismissChatVoiceState() { if (mutableChatRecordingState.value != NqrbChatRecordingState.Recording)
        mutableChatRecordingState.value = if (mutableChatVoiceDraft.value == null) NqrbChatRecordingState.Idle else NqrbChatRecordingState.Preview }

    fun selectTopLevel(destination: NqrbDestination): Boolean {
        require(destination in TOP_LEVEL_DESTINATIONS) { "Destination is not a top-level NQRB destination." }
        if (identity.state.value !is FederatedAuthenticationState.SignedIn) return false
        invalidateDirectChatEntry()
        navigation.selectTopLevel(destination)
        if (destination == NqrbDestination.Home) refreshCallingDirectory()
        if (destination == NqrbDestination.People) {
            refreshContactBook()
            refreshCallingDirectory()
        }
        if (destination == NqrbDestination.History) {
            markCallHistorySeen()
            refreshCallingDirectory()
            refreshCallHistory()
        }
        if (destination == NqrbDestination.Notifications) markAllNotificationsRead()
        if (destination == NqrbDestination.Profile) refreshAccountProfile()
        callActionScope.launch { requestPendingReviewIfReady() }
        return true
    }

    fun markAllNotificationsRead() {
        callActionScope.launch { notificationInbox.markAllRead() }
    }

    private fun markMissedCall(callId: String) {
        if (navigation.current == NqrbDestination.History) {
            acknowledgedMissedCallIds += callId
            transientMissedCallIds -= callId
            mutableMissedCallBadgeCount.value = 0
            return
        }
        if (callId !in acknowledgedMissedCallIds && transientMissedCallIds.add(callId)) {
            mutableMissedCallBadgeCount.value = unseenMissedCallIds(callActivity.state.value.history).size
        }
    }

    private fun refreshMissedCallBadgeFromHistory(history: List<com.botglobal.mobile.platform.calling.CallHistoryItem>) {
        if (navigation.current == NqrbDestination.History) {
            markCallHistorySeen(history)
        } else {
            mutableMissedCallBadgeCount.value = unseenMissedCallIds(history).size
        }
    }

    private fun markCallHistorySeen(history: List<com.botglobal.mobile.platform.calling.CallHistoryItem> = callActivity.state.value.history) {
        acknowledgedMissedCallIds += history.filter(::isMissedCallHistoryItem).map { it.callId }
        acknowledgedMissedCallIds += transientMissedCallIds
        transientMissedCallIds.clear()
        mutableMissedCallBadgeCount.value = 0
    }

    private fun unseenMissedCallIds(history: List<com.botglobal.mobile.platform.calling.CallHistoryItem>): Set<String> =
        (transientMissedCallIds + history.filter(::isMissedCallHistoryItem).map { it.callId })
            .filterNot { it in acknowledgedMissedCallIds }
            .toSet()

    private fun isMissedCallHistoryItem(item: com.botglobal.mobile.platform.calling.CallHistoryItem): Boolean =
        item.outcome.equals("missed", ignoreCase = true)

    fun refreshAccountProfile() {
        callActionScope.launch { loadAccountProfileForCurrentSession() }
    }

    private suspend fun loadAccountProfileForCurrentSession() {
        val request = accountProfileLifecycleMutex.withLock {
            val authenticated = identity.state.value as? FederatedAuthenticationState.SignedIn
            if (authenticated == null) {
                accountProfileRequestGeneration++
                mutableAccountProfileState.value = NqrbAccountProfileState.Hidden
                null
            } else {
                AccountProfileRequest(
                    generation = ++accountProfileRequestGeneration,
                    session = authenticated.session,
                ).also {
                    mutableAccountProfileState.value = NqrbAccountProfileState.Loading
                }
            }
        } ?: return

        val result = accountProfile.load(request.session)
        accountProfileLifecycleMutex.withLock {
            val currentSession = (identity.state.value as? FederatedAuthenticationState.SignedIn)?.session
            if (request.generation != accountProfileRequestGeneration || currentSession != request.session) {
                return
            }

            when (result) {
                is NqrbAccountProfileResult.Available ->
                    mutableAccountProfileState.value = NqrbAccountProfileState.Available(result.profile)
                NqrbAccountProfileResult.AuthenticationRequired -> {
                    accountProfileRequestGeneration++
                    mutableAccountProfileState.value = NqrbAccountProfileState.Hidden
                    identity.logout()
                    chat.bind(null)
                    navigation.reset(NqrbDestination.SignIn)
                }
                NqrbAccountProfileResult.RetryableFailure ->
                    mutableAccountProfileState.value = NqrbAccountProfileState.Failed
            }
        }
    }

    private suspend fun invalidateAccountProfileRequests(hideProfile: Boolean): AccountProfileInvalidation =
        accountProfileLifecycleMutex.withLock {
            val invalidation = AccountProfileInvalidation(
                generation = ++accountProfileRequestGeneration,
                session = (identity.state.value as? FederatedAuthenticationState.SignedIn)?.session,
                wasLoading = mutableAccountProfileState.value == NqrbAccountProfileState.Loading,
            )
            if (hideProfile) {
                mutableAccountProfileState.value = NqrbAccountProfileState.Hidden
            }
            invalidation
        }

    private suspend fun markInvalidatedLoadingProfileAsFailed(invalidation: AccountProfileInvalidation) {
        accountProfileLifecycleMutex.withLock {
            val currentSession = (identity.state.value as? FederatedAuthenticationState.SignedIn)?.session
            if (invalidation.wasLoading &&
                invalidation.generation == accountProfileRequestGeneration &&
                invalidation.session == currentSession
            ) {
                mutableAccountProfileState.value = NqrbAccountProfileState.Failed
            }
        }
    }

    fun canUseHome(): Boolean = identity.state.value is FederatedAuthenticationState.SignedIn

    private suspend fun requiresUpdate(): Boolean {
        val decision = runCatching {
            UpdatePolicyEngine.decide(updatePolicy.versionPolicy(currentVersion, platform))
        }.getOrNull() ?: return false
        return if (decision.mode == UpdateMode.Required) {
            mutableRequiredUpdateMessage.value = decision.message
            mutableRequiredUpdateDestination.value = decision.storeDestination
            true
        } else {
            mutableRequiredUpdateMessage.value = decision.message.takeIf { decision.mode == UpdateMode.Optional }
            mutableRequiredUpdateDestination.value = decision.storeDestination.takeIf { decision.mode == UpdateMode.Optional }
            false
        }
    }

    private fun isCurrentSession(session: MobileSession): Boolean =
        (identity.state.value as? FederatedAuthenticationState.SignedIn)?.session == session

    private fun queueIncomingContactLookup(snapshot: CallSessionSnapshot) {
        if (snapshot.direction != CallDirection.Incoming || snapshot.state != CallState.Ringing) return
        val callId = snapshot.callId?.value ?: return
        val membershipId = snapshot.participant?.membershipId?.takeIf(String::isNotBlank) ?: return
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn ?: return
        if (!requestedCallContacts.add(callId)) return
        callActionScope.launch {
            val session = signedInSessionForAction(signedIn.session) ?: return@launch
            contactBook.resolveContactForCall(session, membershipId)
        }
    }

    private suspend fun signedInSessionForAction(expected: MobileSession): MobileSession? {
        sessionAvailability?.let { source ->
            if (!renewChatAuthority()) return null
            return (source.availability.value as? NqrbSessionAvailability.Online)?.session
                ?.takeIf { it.sameAccountAs(expected) }
        }
        val current = identity.state.value as? FederatedAuthenticationState.SignedIn
            ?: return null
        if (!current.session.sameAccountAs(expected)) return null
        if (!current.session.accessExpiresSoon()) return current.session
        return sessionRenewalMutex.withLock {
            val latest = identity.state.value as? FederatedAuthenticationState.SignedIn
                ?: return@withLock null
            if (!latest.session.sameAccountAs(expected)) return@withLock null
            if (!latest.session.accessExpiresSoon()) return@withLock latest.session
            val refreshed = identity.refreshSignedInSession()
            val afterRefresh = identity.state.value as? FederatedAuthenticationState.SignedIn
            if (afterRefresh == null) {
                contactBook.clear()
                navigation.reset(NqrbDestination.SignIn)
                return@withLock null
            }
            if (!afterRefresh.session.sameAccountAs(expected)) return@withLock null
            (refreshed ?: afterRefresh.session).takeUnless(MobileSession::accessExpiresSoon)
        }
    }

    fun onForeground() {
        chatPlaybackSuspended.value = false
        foreground = true
        foregroundRefreshJob?.cancel()
        foregroundRefreshJob = callActionScope.launch {
            requestReviewIfReady(ReviewTrigger.Foreground)
            while (isActive) {
                if (startupState.value == NqrbStartupState.Ready &&
                    identity.state.value is FederatedAuthenticationState.SignedIn &&
                    (sessionAvailability == null || sessionAvailability.availability.value is NqrbSessionAvailability.Online)
                ) {
                    runCatching { calling.connectSignaling() }
                }
                refreshVisibleData()
                runCatching {
                    if (localChatAvailable()) resumeChatOnline()
                    visibleChatRead?.let { (conversation, sequence) -> markChatRead(conversation, sequence) }
                }
                delay(5 * 60 * 1000L)
            }
        }
    }

    fun onBackground() {
        chatPlaybackSuspended.value = true
        val playbackIntent = invalidateChatPlayback()
        foreground = false
        foregroundRefreshJob?.cancel()
        foregroundRefreshJob = null
        callActionScope.launch {
            if (mutableChatRecordingState.value == NqrbChatRecordingState.Recording) chatVoiceRecorder.cancel()
            if (chatPlaybackIntent.value.generation == playbackIntent) chatVoicePlayer.stop()
            if (mutableChatRecordingState.value == NqrbChatRecordingState.Recording) mutableChatRecordingState.value = NqrbChatRecordingState.BackgroundInterrupted
        }
    }

    private suspend fun refreshVisibleData() {
        if (startupState.value != NqrbStartupState.Ready) return
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn ?: return
        val session = signedInSessionForAction(signedIn.session) ?: return
        when (navigation.current) {
            NqrbDestination.Home, NqrbDestination.People -> {
                contactBook.load(session)
                contactBook.loadBlockedAccounts(session)
                refreshCallingDirectory()
            }
            NqrbDestination.History -> {
                callActivity.loadHistory()
                refreshCallingDirectory()
            }
            NqrbDestination.Profile -> loadAccountProfileForCurrentSession()
            else -> Unit
        }
    }

    fun refreshCallingDirectory() {
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn
            ?: return
        callActionScope.launch {
            if (!isCurrentSession(signedIn.session)) return@launch
            callingDirectory.refresh(signedIn.session.identity.membershipId)
        }
    }

    fun refreshContactBook() {
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn
            ?: return
        callActionScope.launch {
            val session = signedInSessionForAction(signedIn.session) ?: return@launch
            contactBook.load(session)
            contactBook.loadBlockedAccounts(session)
        }
    }

    fun refreshBlockedAccounts() {
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn ?: return
        callActionScope.launch {
            val session = signedInSessionForAction(signedIn.session) ?: return@launch
            contactBook.loadBlockedAccounts(session)
        }
    }

    fun blockNqrbAccount(membershipId: String) {
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn ?: return
        callActionScope.launch {
            val session = signedInSessionForAction(signedIn.session) ?: return@launch
            if (contactBook.block(session, membershipId)) refreshCallingDirectory()
        }
    }

    fun unblockNqrbAccount(membershipId: String) {
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn ?: return
        callActionScope.launch {
            val session = signedInSessionForAction(signedIn.session) ?: return@launch
            if (contactBook.unblock(session, membershipId)) refreshCallingDirectory()
        }
    }

    fun loadMoreNqrbContacts() {
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn
            ?: return
        callActionScope.launch {
            val session = signedInSessionForAction(signedIn.session) ?: return@launch
            contactBook.loadMoreContacts(session)
        }
    }

    fun searchNqrbUsers(query: String) {
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn
            ?: return
        if (query.trim().length < 2) {
            callActionScope.launch {
                if (isCurrentSession(signedIn.session)) contactBook.search(signedIn.session, query)
            }
            return
        }
        callActionScope.launch {
            val session = signedInSessionForAction(signedIn.session) ?: return@launch
            contactBook.search(session, query)
        }
    }

    fun loadMoreNqrbSearchResults() {
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn
            ?: return
        callActionScope.launch {
            val session = signedInSessionForAction(signedIn.session) ?: return@launch
            contactBook.loadMoreSearchResults(session)
        }
    }

    fun addNqrbContact(membershipId: String) {
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn
            ?: return
        callActionScope.launch {
            val session = signedInSessionForAction(signedIn.session) ?: return@launch
            if (contactBook.add(session, membershipId)) {
                refreshCallingDirectory()
            }
        }
    }

    fun addNqrbContactFromCallHistory(callId: String, counterpartMembershipId: String? = null) {
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn
            ?: return
        val contactKey = counterpartMembershipId?.takeIf { it.isNotBlank() } ?: callId
        if (contactKey in mutableAddedHistoryContactCalls.value || !pendingHistoryContactAdds.add(contactKey)) return
        callActionScope.launch {
            try {
                val session = signedInSessionForAction(signedIn.session) ?: return@launch
                if (contactBook.addFromCallHistory(session, callId)) {
                    mutableAddedHistoryContactCalls.value = mutableAddedHistoryContactCalls.value + contactKey
                    refreshCallingDirectory()
                    callActivity.loadHistory()
                    if (callActivity.state.value.selected?.callId == callId) callActivity.loadDetail(callId)
                }
            } finally {
                pendingHistoryContactAdds.remove(contactKey)
            }
        }
    }

    fun selectCallHistoryFilter(filter: CallHistoryFilter) {
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn
            ?: return
        callActionScope.launch {
            if (signedInSessionForAction(signedIn.session) != null) callActivity.loadHistory(filter)
        }
    }

    fun removeNqrbContact(membershipId: String) {
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn
            ?: return
        callActionScope.launch {
            val session = signedInSessionForAction(signedIn.session) ?: return@launch
            if (contactBook.remove(session, membershipId)) {
                mutableAddedHistoryContactCalls.value = mutableAddedHistoryContactCalls.value - membershipId
                refreshCallingDirectory()
                callActivity.loadHistory()
            }
        }
    }

    suspend fun updateNqrbContactNickname(membershipId: String, nickname: String?): Boolean {
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn ?: return false
        val session = signedInSessionForAction(signedIn.session) ?: return false
        return contactBook.updateNickname(session, membershipId, nickname)
    }

    fun createNqrbInvite() {
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn
            ?: return
        callActionScope.launch {
            val session = signedInSessionForAction(signedIn.session) ?: return@launch
            contactBook.createInvite(session)
        }
    }

    fun toggleAccountInviteDetails() = contactBook.toggleAccountInviteDetails()

    fun toggleGuestCallDetails() = contactBook.toggleGuestCallDetails()

    fun createNqrbGuestCallInvite() {
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn
            ?: return
        callActionScope.launch {
            val session = signedInSessionForAction(signedIn.session) ?: return@launch
            contactBook.createGuestCallInvite(session)
        }
    }

    fun revokeNqrbGuestCallInvite() {
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn
            ?: return
        callActionScope.launch {
            val session = signedInSessionForAction(signedIn.session) ?: return@launch
            contactBook.revokeGuestCallInvite(session)
        }
    }

    fun updateNqrbInviteCodeInput(value: String) {
        contactBook.updateInviteCodeInput(value)
    }

    fun previewNqrbInvite() {
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn
            ?: return
        callActionScope.launch {
            val session = signedInSessionForAction(signedIn.session) ?: return@launch
            if (contactBook.previewInvite(session)) {
                pendingInviteCode = null
            }
        }
    }

    fun acceptNqrbInvite() {
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn
            ?: return
        callActionScope.launch {
            val session = signedInSessionForAction(signedIn.session) ?: return@launch
            if (contactBook.acceptInvite(session)) {
                pendingInviteCode = null
                refreshCallingDirectory()
            }
        }
    }

    fun cancelNqrbInviteConfirmation() {
        contactBook.clearInvitePreview()
    }

    fun handleNqrbInviteLink(codeOrLink: String) {
        val normalized = normalizeInviteCode(codeOrLink)
        if (normalized.isBlank()) return
        pendingInviteCode = normalized
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn
        if (signedIn == null) {
            navigation.reset(NqrbDestination.SignIn)
            contactBook.updateInviteCodeInput(normalized)
            return
        }

        navigation.selectTopLevel(NqrbDestination.People)
        contactBook.updateInviteCodeInput(normalized)
        callActionScope.launch {
            val session = signedInSessionForAction(signedIn.session) ?: return@launch
            if (contactBook.previewInvite(session, normalized)) {
                pendingInviteCode = null
            }
        }
    }

    private suspend fun processPendingInvite(session: MobileSession): Boolean {
        val code = pendingInviteCode ?: return false
        contactBook.updateInviteCodeInput(code)
        if (contactBook.previewInvite(session, code)) {
            pendingInviteCode = null
        }
        return true
    }

    fun refreshCallHistory() { withRenewedSession { callActivity.loadHistory() } }
    fun loadMoreCallHistory() { withRenewedSession { callActivity.loadNextHistoryPage() } }
    fun openCallDetail(callId: String) { withRenewedSession { callActivity.loadDetail(callId) } }
    fun closeCallDetail() = callActivity.clearDetail()
    fun resetUsage() { callActionScope.launch { callActivity.resetUsage() } }

    private fun withRenewedSession(action: suspend () -> Unit) {
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn ?: return
        callActionScope.launch {
            if (signedInSessionForAction(signedIn.session) != null) action()
        }
    }

    fun requestOutgoingCall(participant: CallableParticipant) {
        if (calling.state.value.state in OngoingCallStates) {
            mutableShowCurrentCallRequests.tryEmit(Unit)
            return
        }
        val signedIn = identity.state.value as? FederatedAuthenticationState.SignedIn
            ?: return
        if (participant.membershipId == signedIn.session.identity.membershipId) return
        if (contactBook.state.value.blockedAccounts.any { it.membershipId == participant.membershipId }) return
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

    fun confirmIncomingCallPresented(callId: CallId) {
        callActionScope.launch { calling.confirmIncomingPresented(callId) }
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
            requestPendingReviewIfReady()
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

    fun dismissCallStatus() {
        callActionScope.launch { calling.dismissTerminal() }
    }

    fun cancelMicrophoneExplanation() {
        microphoneExplanationVisible.value = false
        pendingOutgoingParticipant = null
        callActionScope.launch { requestPendingReviewIfReady() }
    }

    fun setReviewWorkflowActive(key: String, active: Boolean) {
        require(key.isNotBlank()) { "Review workflow key is required." }
        reviewWorkflowBlockers.value = if (active) {
            reviewWorkflowBlockers.value + key
        } else {
            reviewWorkflowBlockers.value - key
        }
        if (!active) callActionScope.launch { requestPendingReviewIfReady() }
    }

    private suspend fun requestReviewIfReady(trigger: ReviewTrigger) {
        if (pendingReviewTrigger == null) pendingReviewTrigger = trigger
        requestPendingReviewIfReady()
    }

    private suspend fun requestPendingReviewIfReady() {
        val trigger = pendingReviewTrigger ?: return
        if (!isReviewReadyForLaunch() || reviews == null) return
        when (reviews.tryRequest(trigger, ::isReviewReadyForLaunch)) {
            ReviewAttemptResult.Launched,
            ReviewAttemptResult.Failed,
            -> pendingReviewTrigger = null
            ReviewAttemptResult.AlreadyRunning,
            ReviewAttemptResult.Deferred,
            ReviewAttemptResult.NotEligible,
            -> Unit
        }
    }

    private fun isReviewReadyForLaunch(): Boolean =
        foreground &&
            startupState.value == NqrbStartupState.Ready &&
            identity.state.value is FederatedAuthenticationState.SignedIn &&
            !microphoneExplanationVisible.value &&
            !microphonePermissionBlocked.value &&
            mutableAccountActionState.value == NqrbAccountActionState.Idle &&
            mutableAccountProfileState.value != NqrbAccountProfileState.Loading &&
            reviewWorkflowBlockers.value.isEmpty() &&
            calling.state.value.state in ReviewReadyCallStates

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
        private val OngoingCallStates = setOf(
            CallState.Preparing, CallState.Connecting, CallState.Ringing, CallState.Answering,
            CallState.Active, CallState.Reconnecting, CallState.Ending,
        )
        val TOP_LEVEL_DESTINATIONS = setOf(
            NqrbDestination.Home,
            NqrbDestination.History,
            NqrbDestination.Notifications,
            NqrbDestination.People,
            NqrbDestination.Profile,
        )
        private val ReviewReadyCallStates = setOf(
            CallState.Idle,
            CallState.Rejected,
            CallState.Cancelled,
            CallState.Missed,
            CallState.Expired,
            CallState.Ended,
            CallState.Failed,
        )
        private const val ChatStorageDisclosureKey = "chat_voice_storage_disclosure"

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

private data class AccountProfileRequest(
    val generation: Long,
    val session: MobileSession,
)

private data class AccountProfileInvalidation(
    val generation: Long,
    val session: MobileSession?,
    val wasLoading: Boolean,
)

private fun MobileSession.sameAccountAs(other: MobileSession): Boolean =
    identity.membershipId == other.identity.membershipId &&
        identity.applicationKey == other.identity.applicationKey

private fun MobileSession.accessExpiresSoon(): Boolean = runCatching {
    Instant.parse(accessExpiresAtUtc) <= Clock.System.now() + 1.minutes
}.getOrDefault(true)
