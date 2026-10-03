package com.botglobal.nqrb.app.state

import com.botglobal.mobile.platform.appearance.AppearanceController
import com.botglobal.mobile.platform.calling.CallParticipant
import com.botglobal.mobile.platform.calling.CallAudioRoute
import com.botglobal.mobile.platform.calling.CallDirection
import com.botglobal.mobile.platform.calling.CallSessionSnapshot
import com.botglobal.mobile.platform.calling.CallState
import com.botglobal.mobile.platform.calling.CallableParticipant
import com.botglobal.mobile.platform.calling.CallingDirectoryController
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
import com.botglobal.mobile.platform.voice.ManagedVoiceRoomController
import com.botglobal.mobile.platform.voice.VoiceIceConfiguration
import com.botglobal.mobile.platform.voice.VoiceJoinResult
import com.botglobal.mobile.platform.voice.VoiceMediaPeerFactory
import com.botglobal.mobile.platform.voice.VoiceSignalingTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

enum class NqrbDestination {
    SignIn,
    Home,
    History,
    People,
    Profile,
    Settings,
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
    private val push: PushRegistrationLifecycle = UnavailablePushRegistrationLifecycle,
    private val accountDeletion: NqrbAccountDeletionGateway = UnavailableNqrbAccountDeletionGateway,
    private val accountProfile: NqrbAccountProfileGateway = UnavailableNqrbAccountProfileGateway,
    private val localAccountDataCleaner: NqrbLocalAccountDataCleaner = UnavailableNqrbLocalAccountDataCleaner,
    private val permissions: PermissionController = UnavailablePermissionController,
    private val reviews: ReviewCoordinator? = null,
    private val callActionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val startupMutex = Mutex()
    private var startupCompleted = false
    private val mutableStartupState = MutableStateFlow(NqrbStartupState.RestoringSession)
    val startupState = mutableStartupState.asStateFlow()
    val microphoneExplanationVisible = MutableStateFlow(false)
    val microphonePermissionBlocked = MutableStateFlow(false)
    private val mutableAccountActionState = MutableStateFlow(NqrbAccountActionState.Idle)
    val accountActionState = mutableAccountActionState.asStateFlow()
    private val mutableAccountProfileState = MutableStateFlow<NqrbAccountProfileState>(NqrbAccountProfileState.Hidden)
    val accountProfileState = mutableAccountProfileState.asStateFlow()
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
    private val sessionRenewalMutex = Mutex()
    private var foregroundRefreshJob: Job? = null
    private var foreground = false
    private var pendingReviewTrigger: ReviewTrigger? = null
    private val reviewWorkflowBlockers = MutableStateFlow<Set<String>>(emptySet())

    init {
        callActionScope.launch {
            calling.state.collect { snapshot ->
                val callId = snapshot.callId?.value ?: return@collect
                queueIncomingContactLookup(snapshot)
                val usage = snapshot.networkUsage
                if (usage.isFinal && usage.measurementAvailable) {
                    val membershipId = (identity.state.value as? FederatedAuthenticationState.SignedIn)
                        ?.session?.identity?.membershipId ?: return@collect
                    if (!submittedUsageCalls.add(callId)) return@collect
                    callActivity.submit(FinalCallUsage(callId, usage.bytesSent, usage.bytesReceived,
                        usage.connectedDurationSeconds ?: 0), membershipId)
                    if ((usage.connectedDurationSeconds ?: 0) >= 30) {
                        pendingReviewTrigger = ReviewTrigger.CompletedExperience
                        reviews?.recordMeaningfulEvent("nqrb:call:$callId")
                        requestPendingReviewIfReady()
                    }
                }
            }
        }
    }

    suspend fun startup() = startupMutex.withLock {
        if (startupCompleted) return@withLock
        mutableStartupState.value = NqrbStartupState.RestoringSession
        try {
            invalidateAccountProfileRequests(hideProfile = true)
            identity.restore()
            val authenticated = identity.state.value as? FederatedAuthenticationState.SignedIn
            navigation.reset(
                if (authenticated != null) {
                    runCatching { push.activate() }
                    runCatching { calling.connectSignaling() }
                    contactBook.load(authenticated.session)
                    queueIncomingContactLookup(calling.state.value)
                    refreshCallingDirectory()
                    callActivity.flushPending(authenticated.session.identity.membershipId)
                    if (processPendingInvite(authenticated.session)) {
                        NqrbDestination.People
                    } else {
                        NqrbDestination.Home
                    }
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
        invalidateAccountProfileRequests(hideProfile = true)
        identity.signIn(FederatedIdentityProvider.Google)
        val authenticated = identity.state.value as? FederatedAuthenticationState.SignedIn
        if (authenticated != null) {
            invalidateAccountProfileRequests(hideProfile = true)
            runCatching { push.activate() }
            runCatching { calling.connectSignaling() }
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
        val profileInvalidation = invalidateAccountProfileRequests(hideProfile = false)
        mutableAccountActionState.value = NqrbAccountActionState.SigningOut
        val unpair = try {
            push.deactivate()
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
        identity.logout()
        mutableAccountProfileState.value = NqrbAccountProfileState.Hidden
        callingDirectory.clear()
        contactBook.clear()
        pendingInviteCode = null
        callActivity.clear()
        submittedUsageCalls.clear()
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
        runCatching { calling.disconnectSignaling() }
        runCatching { push.clearLocalState() }
        runCatching { localAccountDataCleaner.clear() }
        identity.logout()
        mutableAccountProfileState.value = NqrbAccountProfileState.Hidden
        callingDirectory.clear()
        contactBook.clear()
        pendingInviteCode = null
        callActivity.clear()
        submittedUsageCalls.clear()
        pendingHistoryContactAdds.clear()
        reviewWorkflowBlockers.value = emptySet()
        mutableAddedHistoryContactCalls.value = emptySet()
        navigation.reset(NqrbDestination.SignIn)
        mutableAccountActionState.value = NqrbAccountActionState.Idle
    }

    fun openSettings() {
        navigation.push(NqrbDestination.Settings)
        callActionScope.launch { callActivity.loadUsage() }
    }

    fun selectTopLevel(destination: NqrbDestination): Boolean {
        require(destination in TOP_LEVEL_DESTINATIONS) { "Destination is not a top-level NQRB destination." }
        if (identity.state.value !is FederatedAuthenticationState.SignedIn) return false
        navigation.selectTopLevel(destination)
        if (destination == NqrbDestination.Home) refreshCallingDirectory()
        if (destination == NqrbDestination.People) {
            refreshContactBook()
            refreshCallingDirectory()
        }
        if (destination == NqrbDestination.History) {
            refreshCallingDirectory()
            refreshCallHistory()
        }
        if (destination == NqrbDestination.Profile) refreshAccountProfile()
        callActionScope.launch { requestPendingReviewIfReady() }
        return true
    }

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
        foreground = true
        foregroundRefreshJob?.cancel()
        foregroundRefreshJob = callActionScope.launch {
            requestReviewIfReady(ReviewTrigger.Foreground)
            while (isActive) {
                if (startupState.value == NqrbStartupState.Ready &&
                    identity.state.value is FederatedAuthenticationState.SignedIn
                ) {
                    runCatching { calling.connectSignaling() }
                }
                refreshVisibleData()
                delay(5 * 60 * 1000L)
            }
        }
    }

    fun onBackground() {
        foreground = false
        foregroundRefreshJob?.cancel()
        foregroundRefreshJob = null
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
        val TOP_LEVEL_DESTINATIONS = setOf(
            NqrbDestination.Home,
            NqrbDestination.History,
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
