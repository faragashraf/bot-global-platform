package com.botglobal.lamma.app.state

import com.botglobal.lamma.app.data.AccountDeletionAcceptance
import com.botglobal.mobile.platform.identity.IdentityKind
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.botglobal.lamma.app.data.ApiException
import com.botglobal.lamma.app.data.AutobusFinishRoundRequest
import com.botglobal.lamma.app.data.AutobusRevealRequest
import com.botglobal.lamma.app.data.AutobusSubmitAnswersRequest
import com.botglobal.lamma.app.data.AutobusVoteRequest
import com.botglobal.lamma.app.data.FamilyGamesGateway
import com.botglobal.lamma.app.data.GameSessionSnapshot
import com.botglobal.lamma.app.data.MoveRequest
import com.botglobal.lamma.app.data.RegistrationRequest
import com.botglobal.lamma.app.realtime.GameRealtimeClient
import com.botglobal.lamma.app.realtime.RealtimeConnectSource
import com.botglobal.mobile.platform.device.HapticEvent
import com.botglobal.mobile.platform.device.PermissionController
import com.botglobal.mobile.platform.device.PermissionKind
import com.botglobal.mobile.platform.device.PermissionState
import com.botglobal.mobile.platform.device.UnavailablePermissionController
import com.botglobal.mobile.platform.device.SemanticHaptics
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.identity.FederatedCredentialProvider
import com.botglobal.mobile.platform.identity.FederatedCredentialResult
import com.botglobal.mobile.platform.identity.FederatedIdentityProvider
import com.botglobal.mobile.platform.identity.UnavailableFederatedCredentialProvider
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
import com.botglobal.mobile.platform.realtime.NetworkAvailability
import com.botglobal.mobile.platform.realtime.UnavailableNetworkAvailability
import com.botglobal.mobile.platform.update.UpdateMode
import com.botglobal.mobile.platform.update.UpdatePolicyEngine
import com.botglobal.mobile.platform.reviews.ReviewCoordinator
import com.botglobal.mobile.platform.reviews.ReviewAttemptResult
import com.botglobal.mobile.platform.reviews.ReviewTrigger
import com.botglobal.mobile.platform.reviews.RatingInvitationCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.random.Random
import com.botglobal.mobile.platform.voice.ManagedVoiceRoomController
import com.botglobal.mobile.platform.voice.VoiceMediaPeerFactory
import com.botglobal.mobile.platform.voice.VoiceRoomController
import com.botglobal.mobile.platform.voice.VoiceRoomSnapshot
import com.botglobal.mobile.platform.voice.VoiceRoomState
import com.botglobal.mobile.platform.voice.ManagedVoiceConsentController
import com.botglobal.mobile.platform.voice.VoiceConsentSnapshot
import com.botglobal.mobile.platform.voice.VoiceConsentState

enum class AccountDeletionConfirmation { Explanation, Final }

enum class AppScreen {
    Startup,
    Welcome,
    SignIn,
    Register,
    ProfileCompletion,
    Home,
    Ruleset,
    AutobusSetup,
    CreateOrJoin,
    Lobby,
    Gameplay,
    Result,
    RequiredUpdate,
}

enum class AppLanguage { Arabic, English }

enum class SessionRecoveryState {
    Idle,
    Interrupted,
    Recovering,
    Recovered,
    Unrecoverable,
}

enum class AccountDeletionCleanupStepState { Pending, Failed, Completed }

private data class AutobusActionScope(
    val sessionId: String,
    val matchNumber: Int,
    val round: Int,
    val phase: String,
    val revealCategoryKey: String?,
    val deadlineAtUtc: String?,
)

data class AccountDeletionCleanupState(
    val voiceLeave: AccountDeletionCleanupStepState = AccountDeletionCleanupStepState.Pending,
    val consentEnd: AccountDeletionCleanupStepState = AccountDeletionCleanupStepState.Pending,
    val realtimeStop: AccountDeletionCleanupStepState = AccountDeletionCleanupStepState.Pending,
    val credentialsClear: AccountDeletionCleanupStepState = AccountDeletionCleanupStepState.Pending,
) {
    val completed: Boolean get() =
        voiceLeave == AccountDeletionCleanupStepState.Completed &&
            consentEnd == AccountDeletionCleanupStepState.Completed &&
            realtimeStop == AccountDeletionCleanupStepState.Completed &&
            credentialsClear == AccountDeletionCleanupStepState.Completed
}

interface FamilyGamesAccountDeletionTeardown {
    suspend fun leaveVoice()
    suspend fun endVoiceConsent()
    suspend fun stopRealtime()
    suspend fun clearLocalCredentials()
    fun reset() = Unit
}

data class FamilyGamesUiState(
    val screen: AppScreen = AppScreen.Startup,
    val language: AppLanguage = AppLanguage.Arabic,
    val mobileSession: MobileSession? = null,
    val game: GameSessionSnapshot? = null,
    val connection: RealtimeConnectionState = RealtimeConnectionState.Disconnected,
    val recovery: SessionRecoveryState = SessionRecoveryState.Idle,
    val opponentConnection: OpponentConnectionState = OpponentConnectionState.Unknown,
    val recoveredFromInterruption: Boolean = false,
    val busy: Boolean = false,
    val accountDeletionConfirmation: AccountDeletionConfirmation? = null,
    val accountDeletionAcceptance: AccountDeletionAcceptance? = null,
    val accountDeletionCleanup: AccountDeletionCleanupState? = null,
    val accountDeletionFailed: Boolean = false,
    val errorCode: String? = null,
    val optionalUpdateVisible: Boolean = false,
    val updateMessage: String? = null,
    val storeDestination: String? = null,
    val invitation: GameInvitation? = null,
    val cameraExplanationVisible: Boolean = false,
    val voiceExplanationVisible: Boolean = false,
    val voice: VoiceRoomSnapshot = VoiceRoomSnapshot(VoiceRoomState.Unavailable),
    val voiceConsent: VoiceConsentSnapshot = VoiceConsentSnapshot(),
    val pendingInvitationToken: String? = null,
    val profileDraft: String = "",
    val profileCompletionRequired: Boolean = false,
    val autobusDrafts: Map<String, String> = emptyMap(),
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
    networkAvailability: NetworkAvailability = UnavailableNetworkAvailability,
    private val languagePreferences: ApplicationLanguagePreferences = UnavailableApplicationLanguagePreferences,
    private val recentGameSessionPreferences: RecentGameSessionPreferences = UnavailableRecentGameSessionPreferences,
    private val federatedCredentials: FederatedCredentialProvider = UnavailableFederatedCredentialProvider,
    private val reviews: ReviewCoordinator? = null,
    voiceMediaFactory: VoiceMediaPeerFactory? = null,
    private val accountDeletionTeardown: FamilyGamesAccountDeletionTeardown? = null,
    private val ratingInvitation: RatingInvitationCoordinator? = null,
) {
    private val voice: VoiceRoomController? = voiceMediaFactory?.let {
        ManagedVoiceRoomController(scope, realtime, it)
    }
    private val voiceConsent: ManagedVoiceConsentController? = voiceMediaFactory?.let {
        ManagedVoiceConsentController(scope, realtime)
    }
    private val mutableState = MutableStateFlow(
        FamilyGamesUiState(language = languagePreferences.restore() ?: AppLanguage.Arabic),
    )
    val state: StateFlow<FamilyGamesUiState> = mutableState.asStateFlow()
    private var realtimeEventsJob: Job? = null
    private var realtimeStateJob: Job? = null
    private var networkAvailabilityJob: Job? = null
    private var recoveryJob: Job? = null
    private var transportRestartJob: Job? = null
    private var actionJob: Job? = null
    private var pendingAutobusFinishJob: Job? = null
    private var pendingAutobusRevealJob: Job? = null
    private val dirtyAutobusDraftKeys = mutableSetOf<String>()
    private var realtimeHasConnected = false
    private var recoveryRequired = false
    private var recoveryGeneration = 0L
    private var realtimeSessionId: String? = null
    private var transportOperationInProgress = false
    private var voiceStateJob: Job? = null
    private var voiceConsentStateJob: Job? = null
    private var handledAcceptedVoiceRequestId: String? = null
    private var pendingReviewTrigger: ReviewTrigger? = null

    init {
        voiceStateJob = voice?.let { controller ->
            scope.launch { controller.snapshot.collect { voice ->
                voiceConsent?.mediaStateChanged(voice)
                mutableState.update { it.copy(voice = voice) }
            } }
        }
        voiceConsentStateJob = voiceConsent?.let { controller ->
            scope.launch {
                controller.snapshot.collect { consent ->
                    if (consent.state in setOf(VoiceConsentState.Ended, VoiceConsentState.Unavailable) &&
                        mutableState.value.voice.state !in setOf(
                            VoiceRoomState.Idle, VoiceRoomState.Unavailable, VoiceRoomState.Failed,
                        )) {
                        voice?.leave()
                    }
                    val shouldExplain = consent.state == VoiceConsentState.Accepted &&
                        consent.requestId != null && consent.requestId != handledAcceptedVoiceRequestId
                    if (shouldExplain) handledAcceptedVoiceRequestId = consent.requestId
                    mutableState.update { it.copy(
                        voiceConsent = consent,
                        voiceExplanationVisible = if (shouldExplain) true else it.voiceExplanationVisible,
                    ) }
                }
            }
        }
        networkAvailabilityJob = scope.launch {
            networkAvailability.changes.collect { snapshot ->
                realtime.onNetworkAvailabilityChanged(snapshot)
            }
        }
    }

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
            recentGameSessionPreferences.clear()
            mutableState.update { it.copy(screen = AppScreen.Welcome) }
            return@launchAction
        }

        mutableState.update { it.copy(mobileSession = restored) }
        if (resolvePendingInvitationIfAvailable()) return@launchAction
        val active = restoreRecentGameSession()
            ?: runCatching { gateway.activeSession() }.getOrNull()
        if (active == null) {
            mutableState.update { it.copy(screen = AppScreen.Home) }
        } else {
            onAuthoritativeSnapshot(active)
            connectRealtime(active.sessionId, RealtimeConnectSource.AppStart)
        }
    }

    fun continueAsGuest(displayName: String) = launchAction {
        require(displayName.isNotBlank()) { "display_name_required" }
        val session = gateway.continueAsGuest(displayName.trim())
        recentGameSessionPreferences.clear()
        resetAcceptedDeletionCleanup()
        mutableState.update { it.copy(mobileSession = session, screen = AppScreen.Home,
            accountDeletionAcceptance = null, accountDeletionCleanup = null,
            accountDeletionConfirmation = null, accountDeletionFailed = false) }
        haptics.perform(HapticEvent.Success)
        resolvePendingInvitationIfAvailable()
    }

    fun signIn(userNameOrEmail: String, password: String) = launchAction {
        val session = gateway.login(userNameOrEmail.trim(), password)
        recentGameSessionPreferences.clear()
        resetAcceptedDeletionCleanup()
        mutableState.update { it.copy(mobileSession = session, screen = AppScreen.Home,
            accountDeletionAcceptance = null, accountDeletionCleanup = null,
            accountDeletionConfirmation = null, accountDeletionFailed = false) }
        haptics.perform(HapticEvent.Success)
        resolvePendingInvitationIfAvailable()
    }

    fun register(userName: String, email: String, displayName: String, password: String) = launchAction {
        val session = gateway.register(RegistrationRequest(userName, email, displayName, password))
        recentGameSessionPreferences.clear()
        resetAcceptedDeletionCleanup()
        mutableState.update { it.copy(mobileSession = session, screen = AppScreen.Home,
            accountDeletionAcceptance = null, accountDeletionCleanup = null,
            accountDeletionConfirmation = null, accountDeletionFailed = false) }
        haptics.perform(HapticEvent.Success)
        resolvePendingInvitationIfAvailable()
    }

    fun signInWithGoogle() = launchAction {
        when (val acquired = federatedCredentials.acquire(FederatedIdentityProvider.Google)) {
            is FederatedCredentialResult.Acquired -> {
                val session = gateway.authenticateFederated(acquired.credential)
                recentGameSessionPreferences.clear()
                resetAcceptedDeletionCleanup()
                mutableState.update {
                    it.copy(
                        mobileSession = session,
                        screen = AppScreen.ProfileCompletion,
                        profileDraft = session.identity.displayName,
                        profileCompletionRequired = true,
                        accountDeletionAcceptance = null,
                        accountDeletionCleanup = null,
                        accountDeletionConfirmation = null,
                        accountDeletionFailed = false,
                    )
                }
                haptics.perform(HapticEvent.Success)
            }
            FederatedCredentialResult.Cancelled -> mutableState.update { it.copy(errorCode = null) }
            FederatedCredentialResult.ConfigurationMissing -> mutableState.update { it.copy(errorCode = "google_configuration_missing") }
            FederatedCredentialResult.Unavailable -> mutableState.update { it.copy(errorCode = "google_unavailable") }
            FederatedCredentialResult.Failed -> mutableState.update { it.copy(errorCode = "google_failed") }
        }
    }

    fun updateProfileDraft(displayName: String) {
        mutableState.update { it.copy(profileDraft = displayName.take(120), errorCode = null) }
    }

    fun completeProfile() = launchAction {
        val name = mutableState.value.profileDraft.trim()
        if (name.isBlank()) {
            mutableState.update { it.copy(errorCode = "display_name_required") }
            haptics.perform(HapticEvent.Warning)
            return@launchAction
        }
        val session = gateway.updateProfile(name)
        mutableState.update {
            it.copy(
                mobileSession = session,
                profileDraft = session.identity.displayName,
                profileCompletionRequired = false,
            )
        }
        if (resolvePendingInvitationIfAvailable()) return@launchAction
        mutableState.update { it.copy(screen = AppScreen.Home) }
    }

    fun editProfile() {
        val identity = mutableState.value.mobileSession?.identity ?: return
        if (identity.kind != IdentityKind.Registered) return
        mutableState.update {
            it.copy(
                screen = AppScreen.ProfileCompletion,
                profileDraft = identity.displayName,
                profileCompletionRequired = false,
                errorCode = null,
            )
        }
    }

    fun showSignIn() = navigate(AppScreen.SignIn)
    fun showRegister() = navigate(AppScreen.Register)
    fun showRuleset() = navigate(AppScreen.Ruleset)
    fun showAutobusSetup() = navigate(AppScreen.AutobusSetup)
    fun showCreateOrJoin() = navigate(AppScreen.CreateOrJoin)
    fun backToWelcome() = navigate(AppScreen.Welcome)
    fun backHome() = navigate(AppScreen.Home)

    fun toggleLanguage() {
        val selected = if (mutableState.value.language == AppLanguage.Arabic) {
            AppLanguage.English
        } else {
            AppLanguage.Arabic
        }
        languagePreferences.save(selected)
        mutableState.update { it.copy(language = selected) }
    }

    fun dismissOptionalUpdate() {
        mutableState.update { it.copy(optionalUpdateVisible = false) }
    }

    fun createClassicGame() = launchAction {
        val snapshot = gateway.createSession("classic-3x3")
        onAuthoritativeSnapshot(snapshot)
        connectRealtime(snapshot.sessionId, RealtimeConnectSource.SessionCreated)
    }

    fun createAutobusGame(
        rounds: Int = 5,
        seconds: Int = 60,
        difficulty: String = "medium",
        categories: List<String> = DefaultAutobusCategories,
    ) = launchAction {
        val snapshot = gateway.createAutobusSession(rounds, seconds, difficulty, categories)
        onAuthoritativeSnapshot(snapshot)
        connectRealtime(snapshot.sessionId, RealtimeConnectSource.SessionCreated)
    }

    fun joinGame(code: String) = launchAction {
        val snapshot = gateway.joinSession(code)
        onAuthoritativeSnapshot(snapshot)
        connectRealtime(snapshot.sessionId, RealtimeConnectSource.SessionJoined)
    }

    fun showInvitation() = launchAction {
        val invitation = gateway.createInvitation(requireGame().sessionId)
        mutableState.update { it.copy(invitation = invitation) }
        haptics.perform(HapticEvent.LightImpact)
    }

    fun dismissInvitation() {
        mutableState.update { it.copy(invitation = null) }
        requestPendingReviewIfReady()
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
        requestPendingReviewIfReady()
    }

    fun showVoiceExplanation() {
        if (mutableState.value.game?.ruleset?.voiceEnabled != true || voice == null ||
            mutableState.value.voiceConsent.state != VoiceConsentState.Accepted) {
            mutableState.update { it.copy(errorCode = "voice_unavailable") }
            return
        }
        mutableState.update { it.copy(voiceExplanationVisible = true, errorCode = null) }
    }

    fun dismissVoiceExplanation() {
        mutableState.update { it.copy(voiceExplanationVisible = false) }
        requestPendingReviewIfReady()
    }

    fun requestVoiceChat() = launchAction { voiceConsent?.request() }
    fun acceptVoiceRequest() = launchAction { voiceConsent?.accept() }
    fun declineVoiceRequest() = launchAction { voiceConsent?.decline() }
    fun cancelVoiceRequest() = launchAction { voiceConsent?.cancel() }

    fun confirmMicrophoneAndJoinVoice() = launchAction {
        mutableState.update { it.copy(voiceExplanationVisible = false) }
        if (mutableState.value.voiceConsent.state != VoiceConsentState.Accepted) return@launchAction
        val current = permissions.state(PermissionKind.Microphone)
        val permission = if (current == PermissionState.Granted) current
        else permissions.requestAfterExplanation(PermissionKind.Microphone)
        if (permission != PermissionState.Granted) {
            mutableState.update {
                it.copy(errorCode = if (permission == PermissionState.PermanentlyDenied) {
                    "microphone_permission_permanently_denied"
                } else "microphone_permission_denied")
            }
            haptics.perform(HapticEvent.Warning)
            voiceConsent?.unavailable("microphone_permission_denied")
            return@launchAction
        }
        voice?.join(requireGame().sessionId)
    }

    fun toggleVoiceMute() {
        val controller = voice ?: return
        scope.launch { controller.setMuted(!mutableState.value.voice.muted) }
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
        if (
            mutableState.value.connection != RealtimeConnectionState.Connected ||
            realtime.connectionState.value != RealtimeConnectionState.Connected
        ) {
            haptics.perform(HapticEvent.Warning)
            return@launchAction
        }
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

    fun updateAutobusAnswer(categoryKey: String, answer: String) {
        dirtyAutobusDraftKeys += categoryKey
        mutableState.update {
            it.copy(
                autobusDrafts = it.autobusDrafts + (categoryKey to answer.take(48)),
                errorCode = null,
            )
        }
    }

    fun submitAutobusAnswers() = launchAction {
        val submittedDrafts = currentAutobusDrafts()
        performAutobusCommand(retryAfterRecovery = true) { game ->
            val autobus = game.autobus ?: error("autobus_state_missing")
            gateway.submitAutobusAnswers(
                AutobusSubmitAnswersRequest(
                    game.sessionId,
                    commandId(),
                    game.version,
                    game.matchNumber,
                    autobus.currentRound,
                    autobus.categories.associate { it.key to submittedDrafts[it.key].orEmpty() },
                ),
            )
        }?.let { snapshot ->
            acknowledgeAutobusDrafts(submittedDrafts)
            onAuthoritativeSnapshot(snapshot)
        }
        haptics.perform(HapticEvent.Selection)
    }

    fun finishAutobusRound() {
        val requestedScope = mutableState.value.game?.autobusActionScope()
            ?.takeIf { it.phase in setOf("active", "grace") }
            ?: return
        val runningAction = actionJob
        if (runningAction?.isActive == true) {
            if (pendingAutobusFinishJob?.isActive != true) {
                pendingAutobusFinishJob = scope.launch {
                    runningAction.join()
                    pendingAutobusFinishJob = null
                    if (mutableState.value.game?.autobusActionScope() == requestedScope) {
                        finishAutobusRound()
                    }
                }
            }
            return
        }

        launchAction {
            val current = requireGame()
            if (current.autobusActionScope() != requestedScope) return@launchAction
            val submittedDrafts = currentAutobusDrafts()
            val draftsSubmitted = current.autobus?.acceptsDraftsAt(Clock.System.now()) == true
            val submitted = if (draftsSubmitted) {
                performAutobusCommand(retryAfterRecovery = true) { game ->
                    val autobus = game.autobus ?: error("autobus_state_missing")
                    gateway.submitAutobusAnswers(
                        AutobusSubmitAnswersRequest(
                            game.sessionId,
                            commandId(),
                            game.version,
                            game.matchNumber,
                            autobus.currentRound,
                            autobus.categories.associate { it.key to submittedDrafts[it.key].orEmpty() },
                        ),
                    )
                } ?: return@launchAction
            } else {
                current
            }
            if (draftsSubmitted) acknowledgeAutobusDrafts(submittedDrafts)
            onAuthoritativeSnapshot(submitted)
            if (submitted.autobusActionScope() != requestedScope) return@launchAction
            performAutobusCommand { game ->
                gateway.finishAutobusRound(
                    AutobusFinishRoundRequest(game.sessionId, commandId(), game.version),
                )
            }?.let(::onAuthoritativeSnapshot)
            haptics.perform(HapticEvent.ImportantAction)
        }
    }

    fun revealAutobus() {
        val requestedScope = mutableState.value.game?.autobusActionScope()
            ?.takeIf { it.phase == "reveal" }
            ?: return
        val runningAction = actionJob
        if (runningAction?.isActive == true) {
            if (pendingAutobusRevealJob?.isActive != true) {
                pendingAutobusRevealJob = scope.launch {
                    runningAction.join()
                    pendingAutobusRevealJob = null
                    if (mutableState.value.game?.autobusActionScope() == requestedScope) {
                        revealAutobus()
                    }
                }
            }
            return
        }

        launchAction {
            if (requireGame().autobusActionScope() != requestedScope) return@launchAction
            performAutobusCommand(
                retryAfterRecovery = true,
                canRetryAfterRecovery = { _, recovered ->
                    recovered.autobusActionScope() == requestedScope
                },
            ) { game ->
                gateway.revealAutobus(
                    AutobusRevealRequest(game.sessionId, commandId(), game.version),
                )
            }?.let(::onAuthoritativeSnapshot)
            haptics.perform(HapticEvent.GameEvent)
        }
    }

    fun voteAutobus(answerOwnerMembershipId: String, categoryKey: String, accept: Boolean) = launchAction {
        performAutobusCommand { game ->
            gateway.voteAutobus(
                AutobusVoteRequest(
                    game.sessionId,
                    commandId(),
                    game.version,
                    answerOwnerMembershipId,
                    categoryKey,
                    accept,
                ),
            )
        }?.let(::onAuthoritativeSnapshot)
        haptics.perform(HapticEvent.Selection)
    }

    fun requestRematch() = launchAction {
        onAuthoritativeSnapshot(gateway.requestRematch(requireGame().sessionId))
        haptics.perform(HapticEvent.ImportantAction)
    }

    fun acceptRematch() = launchAction {
        onAuthoritativeSnapshot(gateway.acceptRematch(requireGame().sessionId))
        haptics.perform(HapticEvent.Success)
    }

    fun retryRealtime() {
        val sessionId = mutableState.value.game?.sessionId ?: return
        when (realtime.connectionState.value) {
            RealtimeConnectionState.Connected -> {
                recoveryRequired = true
                requestAuthoritativeRecovery(sessionId)
            }
            RealtimeConnectionState.Connecting,
            RealtimeConnectionState.Reconnecting,
            -> Unit
            else -> restartRealtimeTransport(sessionId, RealtimeConnectSource.ManualRetry)
        }
    }

    fun resumeAfterForeground() {
        val sessionId = mutableState.value.game?.sessionId ?: return
        if (mutableState.value.screen !in GameScreens) return
        if (transportOperationInProgress || recoveryJob?.isActive == true) return
        if (mutableState.value.voice.state == VoiceRoomState.Reconnecting &&
            realtime.connectionState.value == RealtimeConnectionState.Connected
        ) {
            scope.launch { voice?.signalingRecovered() }
        }
        when (realtime.connectionState.value) {
            RealtimeConnectionState.Connected -> {
                recoveryRequired = true
                requestAuthoritativeRecovery(sessionId)
            }
            RealtimeConnectionState.Connecting,
            RealtimeConnectionState.Reconnecting,
            -> Unit
            else -> restartRealtimeTransport(sessionId, RealtimeConnectSource.Foreground)
        }
        requestReviewIfReady(ReviewTrigger.Foreground)
    }

    fun pauseForBackground() {
        if (mutableState.value.voice.state !in setOf(VoiceRoomState.Idle, VoiceRoomState.Unavailable, VoiceRoomState.Failed)) {
            scope.launch { voice?.signalingInterrupted() }
        }
    }

    fun exitGame() = launchAction {
        resetRecoveryOrchestration()
        voice?.leave()
        voiceConsent?.end()
        realtime.stop()
        recentGameSessionPreferences.clear()
        dirtyAutobusDraftKeys.clear()
        mutableState.update {
            it.copy(
                game = null,
                invitation = null,
                screen = AppScreen.Home,
                connection = RealtimeConnectionState.Disconnected,
                recovery = SessionRecoveryState.Idle,
                recoveredFromInterruption = false,
                errorCode = null,
                voice = VoiceRoomSnapshot(),
                voiceConsent = VoiceConsentSnapshot(),
                autobusDrafts = emptyMap(),
            )
        }
        requestReviewIfReady(ReviewTrigger.ExplicitExit)
    }

    fun beginAccountDeletion() {
        if (mutableState.value.busy || actionJob?.isActive == true || mutableState.value.screen != AppScreen.Home ||
            mutableState.value.mobileSession?.identity?.kind != IdentityKind.Registered) return
        mutableState.update { it.copy(accountDeletionConfirmation = AccountDeletionConfirmation.Explanation,
            accountDeletionFailed = false) }
    }

    fun confirmAccountDeletionExplanation() {
        if (mutableState.value.busy ||
            mutableState.value.accountDeletionConfirmation != AccountDeletionConfirmation.Explanation) return
        mutableState.update { it.copy(accountDeletionConfirmation = AccountDeletionConfirmation.Final) }
    }

    fun cancelAccountDeletion() {
        if (mutableState.value.busy || actionJob?.isActive == true ||
            mutableState.value.accountDeletionAcceptance != null) return
        mutableState.update { it.copy(accountDeletionConfirmation = null, accountDeletionFailed = false) }
        requestPendingReviewIfReady()
    }

    fun deleteAccount() {
        if (mutableState.value.accountDeletionConfirmation != AccountDeletionConfirmation.Final ||
            mutableState.value.accountDeletionAcceptance == null &&
            mutableState.value.mobileSession?.identity?.kind != IdentityKind.Registered) return
        launchAction {
            mutableState.update { it.copy(accountDeletionFailed = false) }
            try {
                val acceptance = mutableState.value.accountDeletionAcceptance ?: gateway.deleteAccount()
                mutableState.update { it.copy(
                    accountDeletionAcceptance = acceptance,
                    accountDeletionCleanup = it.accountDeletionCleanup ?: AccountDeletionCleanupState(),
                ) }
                // Once accepted, cancellation must not interrupt local credential removal.
                withContext(NonCancellable) {
                    retryAcceptedDeletionCleanup(acceptance)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update { it.copy(accountDeletionFailed = true) }
            }
        }
    }

    fun logout() = launchAction { finishSession() }

    private suspend fun finishSession() {
        resetRecoveryOrchestration()
        runCatching { voice?.leave() }
        runCatching { voiceConsent?.end() }
        runCatching { realtime.stop() }
        gateway.logout()
        recentGameSessionPreferences.clear()
        mutableState.value = FamilyGamesUiState(
            screen = AppScreen.Welcome,
            language = mutableState.value.language,
        )
    }

    private suspend fun retryAcceptedDeletionCleanup(acceptance: AccountDeletionAcceptance) {
        resetRecoveryOrchestration()
        var progress = mutableState.value.accountDeletionCleanup ?: AccountDeletionCleanupState()

        suspend fun attempt(
            current: AccountDeletionCleanupStepState,
            update: (AccountDeletionCleanupStepState) -> AccountDeletionCleanupState,
            operation: suspend () -> Unit,
        ) {
            if (current == AccountDeletionCleanupStepState.Completed) return
            progress = try {
                operation()
                update(AccountDeletionCleanupStepState.Completed)
            } catch (_: Throwable) {
                update(AccountDeletionCleanupStepState.Failed)
            }
            mutableState.update { it.copy(accountDeletionCleanup = progress) }
        }

        attempt(progress.voiceLeave, { progress.copy(voiceLeave = it) }) {
            accountDeletionTeardown?.leaveVoice() ?: voice?.leaveForAccountDeletion()
        }
        attempt(progress.consentEnd, { progress.copy(consentEnd = it) }) {
            accountDeletionTeardown?.endVoiceConsent() ?: voiceConsent?.endForAccountDeletion()
        }
        attempt(progress.realtimeStop, { progress.copy(realtimeStop = it) }) {
            accountDeletionTeardown?.stopRealtime() ?: realtime.stop()
        }
        attempt(progress.credentialsClear, { progress.copy(credentialsClear = it) }) {
            accountDeletionTeardown?.clearLocalCredentials() ?: gateway.clearLocalSession()
        }

        if (progress.credentialsClear == AccountDeletionCleanupStepState.Completed) {
            recentGameSessionPreferences.clear()
            mutableState.update { it.copy(mobileSession = null, game = null, invitation = null) }
        }
        if (progress.completed) {
            mutableState.value = FamilyGamesUiState(
                screen = AppScreen.Welcome,
                language = mutableState.value.language,
                accountDeletionAcceptance = acceptance,
                accountDeletionCleanup = progress,
            )
        } else {
            mutableState.update { it.copy(accountDeletionFailed = true, accountDeletionCleanup = progress) }
        }
    }

    private fun resetAcceptedDeletionCleanup() {
        accountDeletionTeardown?.reset()
        voice?.resetAccountDeletionCleanup()
        voiceConsent?.resetAccountDeletionCleanup()
    }

    fun dispose() {
        actionJob?.cancel()
        pendingAutobusFinishJob?.cancel()
        pendingAutobusRevealJob?.cancel()
        realtimeEventsJob?.cancel()
        realtimeStateJob?.cancel()
        networkAvailabilityJob?.cancel()
        voiceStateJob?.cancel()
        voiceConsentStateJob?.cancel()
        scope.launch { voice?.leave() }
        resetRecoveryOrchestration()
    }

    private fun navigate(screen: AppScreen) {
        mutableState.update { it.copy(screen = screen, errorCode = null) }
    }

    private suspend fun connectRealtime(sessionId: String, source: RealtimeConnectSource) {
        resetRecoveryOrchestration()
        realtimeSessionId = sessionId
        realtimeEventsJob?.cancel()
        realtimeStateJob?.cancel()
        realtimeEventsJob = scope.launch {
            realtime.events.collect { event ->
                val accepted = onAuthoritativeSnapshot(event.snapshot)
                if (accepted &&
                    recoveryRequired &&
                    realtime.connectionState.value == RealtimeConnectionState.Connected
                ) {
                    completeRecoveryFromAuthoritativeEvent(sessionId)
                }
            }
        }
        realtimeStateJob = scope.launch {
            realtime.connectionState.collect { connection ->
                onRealtimeConnectionState(sessionId, connection)
            }
        }
        transportOperationInProgress = true
        try {
            realtime.start(sessionId, source) { gateway.restore()?.accessToken }
            runCatching { voiceConsent?.reconcile() }
        } finally {
            transportOperationInProgress = false
        }
    }

    private fun onRealtimeConnectionState(
        sessionId: String,
        connection: RealtimeConnectionState,
    ) {
        if (realtimeSessionId != sessionId || mutableState.value.game?.sessionId != sessionId) return
        when (connection) {
            RealtimeConnectionState.Connected -> {
                val needsRecovery = recoveryRequired
                realtimeHasConnected = true
                if (needsRecovery) {
                    mutableState.update {
                        it.copy(
                            connection = RealtimeConnectionState.Reconnecting,
                            recovery = SessionRecoveryState.Recovering,
                            recoveredFromInterruption = false,
                            errorCode = null,
                        )
                    }
                    requestAuthoritativeRecovery(sessionId)
                } else {
                    mutableState.update {
                        it.copy(
                            connection = RealtimeConnectionState.Connected,
                            recovery = SessionRecoveryState.Idle,
                            errorCode = null,
                        )
                    }
                }
                if (mutableState.value.voice.state == VoiceRoomState.Reconnecting) {
                    scope.launch { voice?.signalingRecovered() }
                }
            }

            RealtimeConnectionState.Reconnecting,
            RealtimeConnectionState.Failed,
            RealtimeConnectionState.Disconnected,
            -> {
                if (mutableState.value.voice.state !in setOf(VoiceRoomState.Idle, VoiceRoomState.Unavailable, VoiceRoomState.Failed)) {
                    scope.launch { voice?.signalingInterrupted() }
                }
                if (realtimeHasConnected || recoveryRequired) {
                    interruptRecovery(connection)
                } else {
                    mutableState.update {
                        it.copy(
                            connection = connection,
                            recovery = SessionRecoveryState.Idle,
                            recoveredFromInterruption = false,
                        )
                    }
                }
            }

            RealtimeConnectionState.Connecting -> mutableState.update {
                it.copy(
                    connection = if (recoveryRequired) {
                        RealtimeConnectionState.Reconnecting
                    } else {
                        RealtimeConnectionState.Connecting
                    },
                    recovery = if (recoveryRequired) {
                        SessionRecoveryState.Interrupted
                    } else {
                        SessionRecoveryState.Idle
                    },
                    recoveredFromInterruption = false,
                )
            }

            RealtimeConnectionState.Unavailable -> mutableState.update {
                it.copy(
                    connection = connection,
                    recovery = SessionRecoveryState.Unrecoverable,
                    recoveredFromInterruption = false,
                )
            }
        }
    }

    private fun interruptRecovery(connection: RealtimeConnectionState) {
        recoveryRequired = true
        invalidateRecoveryAttempt()
        mutableState.update {
            it.copy(
                connection = connection,
                recovery = SessionRecoveryState.Interrupted,
                recoveredFromInterruption = false,
                errorCode = it.errorCode.takeUnless { code -> code == "recovery_failed" },
            )
        }
    }

    private fun requestAuthoritativeRecovery(sessionId: String) {
        if (realtimeSessionId != sessionId || recoveryJob?.isActive == true) return
        recoveryRequired = true
        val attempt = ++recoveryGeneration
        mutableState.update {
            it.copy(
                connection = RealtimeConnectionState.Reconnecting,
                recovery = SessionRecoveryState.Recovering,
                recoveredFromInterruption = false,
                errorCode = null,
            )
        }
        recoveryJob = scope.launch {
            recoverAuthoritativeState(sessionId, attempt)
        }
    }

    private suspend fun recoverAuthoritativeState(sessionId: String, attempt: Long) {
        RecoveryRetryBackoff.forEachIndexed { index, backoffMillis ->
            if (!isCurrentRecovery(sessionId, attempt)) return
            if (backoffMillis > 0) delay(backoffMillis)
            if (!isCurrentRecovery(sessionId, attempt)) return
            try {
                publishRecoverySuccess(
                    sessionId,
                    attempt,
                    withTimeoutOrNull(RecoveryRequestTimeoutMillis) {
                        gateway.rejoin(sessionId)
                    } ?: throw IllegalStateException("Authoritative recovery timed out."),
                )
                runCatching { voiceConsent?.reconcile() }
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiException) {
                if (error.code in AuthoritativeUnrecoverableRecoveryErrors) {
                    publishUnrecoverableRecovery(sessionId, attempt)
                    return
                }
                if (index == RecoveryRetryBackoff.lastIndex) {
                    publishTransientRecoveryExhausted(sessionId, attempt)
                    return
                }
            } catch (_: Throwable) {
                if (index == RecoveryRetryBackoff.lastIndex) {
                    publishTransientRecoveryExhausted(sessionId, attempt)
                    return
                }
            }
        }
    }

    private fun publishRecoverySuccess(
        sessionId: String,
        attempt: Long,
        snapshot: GameSessionSnapshot,
    ) {
        if (!isCurrentRecovery(sessionId, attempt)) return
        onAuthoritativeSnapshot(
            snapshot,
            recoveredFromInterruption = true,
            forceReplace = true,
        )
        if (!isCurrentRecovery(sessionId, attempt)) return
        recoveryRequired = false
        mutableState.update {
            it.copy(
                connection = RealtimeConnectionState.Connected,
                recovery = SessionRecoveryState.Recovered,
                recoveredFromInterruption = true,
                errorCode = null,
            )
        }
    }

    private fun publishUnrecoverableRecovery(sessionId: String, attempt: Long) {
        if (!isCurrentRecovery(sessionId, attempt)) return
        mutableState.update {
            it.copy(
                connection = RealtimeConnectionState.Failed,
                recovery = SessionRecoveryState.Unrecoverable,
                recoveredFromInterruption = false,
                errorCode = "recovery_failed",
            )
        }
        haptics.perform(HapticEvent.Error)
    }

    private fun publishTransientRecoveryExhausted(sessionId: String, attempt: Long) {
        if (!isCurrentRecovery(sessionId, attempt)) return
        mutableState.update {
            it.copy(
                connection = RealtimeConnectionState.Failed,
                recovery = SessionRecoveryState.Interrupted,
                recoveredFromInterruption = false,
                errorCode = null,
            )
        }
    }

    private fun completeRecoveryFromAuthoritativeEvent(sessionId: String) {
        if (realtimeSessionId != sessionId || !recoveryRequired) return
        recoveryRequired = false
        invalidateRecoveryAttempt()
        mutableState.update {
            it.copy(
                connection = RealtimeConnectionState.Connected,
                recovery = SessionRecoveryState.Recovered,
                recoveredFromInterruption = true,
                errorCode = null,
            )
        }
    }

    private fun restartRealtimeTransport(sessionId: String, source: RealtimeConnectSource) {
        if (transportOperationInProgress || transportRestartJob?.isActive == true) return
        recoveryRequired = true
        transportRestartJob = scope.launch {
            transportOperationInProgress = true
            try {
                realtime.start(sessionId, source) { gateway.restore()?.accessToken }
            } catch (_: Throwable) {
                if (realtimeSessionId == sessionId) {
                    mutableState.update {
                        it.copy(
                            connection = RealtimeConnectionState.Failed,
                            recovery = SessionRecoveryState.Interrupted,
                            recoveredFromInterruption = false,
                            errorCode = null,
                        )
                    }
                }
            } finally {
                transportOperationInProgress = false
            }
        }
    }

    private fun isCurrentRecovery(sessionId: String, attempt: Long): Boolean =
        realtimeSessionId == sessionId && recoveryGeneration == attempt

    private fun invalidateRecoveryAttempt() {
        recoveryGeneration++
        recoveryJob?.cancel()
        recoveryJob = null
    }

    private fun resetRecoveryOrchestration() {
        invalidateRecoveryAttempt()
        transportRestartJob?.cancel()
        transportRestartJob = null
        realtimeHasConnected = false
        recoveryRequired = false
        realtimeSessionId = null
        transportOperationInProgress = false
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
        connectRealtime(snapshot.sessionId, RealtimeConnectSource.InvitationResolved)
        haptics.perform(HapticEvent.Success)
    }

    private fun onAuthoritativeSnapshot(
        snapshot: GameSessionSnapshot,
        recoveredFromInterruption: Boolean = false,
        forceReplace: Boolean = false,
    ): Boolean {
        val current = mutableState.value.game
        if (current == null || current.sessionId != snapshot.sessionId || current.matchNumber != snapshot.matchNumber) {
            if (current != null) scope.launch { voice?.leave() }
            handledAcceptedVoiceRequestId = null
            voiceConsent?.bind(snapshot.sessionId, snapshot.matchNumber)
        }
        if (!forceReplace && current != null && current.sessionId == snapshot.sessionId) {
            if (snapshot.matchNumber < current.matchNumber) return false
            if (snapshot.matchNumber == current.matchNumber && snapshot.version < current.version) return false
            if (snapshot.matchNumber == current.matchNumber &&
                snapshot.version == current.version &&
                isOlderSessionRevision(snapshot, current)
            ) return false
        }
        recentGameSessionPreferences.save(snapshot.sessionId)
        val screen = when (snapshot.status) {
            "completed" -> AppScreen.Result
            "started" -> AppScreen.Gameplay
            else -> AppScreen.Lobby
        }
        emitGameFeedback(current, snapshot)
        mutableState.update {
            val localMembershipId = it.mobileSession?.identity?.membershipId
            val ownAnswers = snapshot.autobus?.answers
                ?.filter { answer -> answer.playerMembershipId == localMembershipId }
                .orEmpty()
                .associate { answer -> answer.categoryKey to answer.displayAnswer }
            val sameAutobusRound = current?.sessionId == snapshot.sessionId &&
                current.matchNumber == snapshot.matchNumber &&
                current.autobus?.currentRound == snapshot.autobus?.currentRound
            val activeAutobusDrafts = when {
                snapshot.autobus?.phase !in setOf("active", "grace") -> {
                    dirtyAutobusDraftKeys.clear()
                    emptyMap()
                }
                sameAutobusRound -> (snapshot.autobus?.categories.orEmpty().map { category -> category.key } +
                    it.autobusDrafts.keys + ownAnswers.keys).distinct().associateWith { categoryKey ->
                    val localDraft = it.autobusDrafts[categoryKey].orEmpty()
                    if (categoryKey in dirtyAutobusDraftKeys) {
                        localDraft
                    } else {
                        ownAnswers[categoryKey] ?: localDraft
                    }
                }
                else -> {
                    dirtyAutobusDraftKeys.clear()
                    ownAnswers
                }
            }
            it.copy(
                game = snapshot,
                screen = screen,
                opponentConnection = snapshot.opponentConnectionState(it.mobileSession?.identity?.membershipId),
                errorCode = null,
                recoveredFromInterruption = recoveredFromInterruption,
                autobusDrafts = activeAutobusDrafts,
            )
        }
        if (current?.status != "completed" && snapshot.status == "completed") {
            pendingReviewTrigger = ReviewTrigger.CompletedExperience
            scope.launch {
                ratingInvitation?.recordMeaningfulEvent("lamma:${snapshot.sessionId}:${snapshot.matchNumber}")
                reviews?.recordMeaningfulEvent("lamma:${snapshot.sessionId}:${snapshot.matchNumber}")
                requestPendingReviewIfReady()
            }
        }
        return true
    }

    private suspend fun restoreRecentGameSession(): GameSessionSnapshot? {
        val sessionId = recentGameSessionPreferences.restore()?.takeIf { it.isNotBlank() } ?: return null
        return try {
            gateway.rejoin(sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ApiException) {
            if (error.code in AuthoritativeUnrecoverableRecoveryErrors) {
                recentGameSessionPreferences.clear()
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun currentAutobusDrafts(): Map<String, String> =
        mutableState.value.autobusDrafts.toMap()

    private fun acknowledgeAutobusDrafts(submittedDrafts: Map<String, String>) {
        val currentDrafts = mutableState.value.autobusDrafts
        submittedDrafts.forEach { (categoryKey, submittedValue) ->
            if (currentDrafts[categoryKey].orEmpty() == submittedValue) {
                dirtyAutobusDraftKeys -= categoryKey
            }
        }
    }

    private fun requestReviewIfReady(trigger: ReviewTrigger) {
        pendingReviewTrigger = trigger
        requestPendingReviewIfReady()
    }

    private fun requestPendingReviewIfReady() {
        val trigger = pendingReviewTrigger ?: return
        if (ratingInvitation?.suppressesNativePrompt() == true) return
        if (!isReviewReadyForLaunch(trigger)) return
        scope.launch {
            when (reviews?.tryRequest(trigger) {
                ratingInvitation?.suppressesNativePrompt() != true && isReviewReadyForLaunch(trigger)
            }) {
                ReviewAttemptResult.Launched,
                ReviewAttemptResult.Failed,
                null,
                -> pendingReviewTrigger = null
                ReviewAttemptResult.AlreadyRunning,
                ReviewAttemptResult.Deferred,
                ReviewAttemptResult.NotEligible,
                -> Unit
            }
        }
    }

    private fun isReviewReadyForLaunch(trigger: ReviewTrigger): Boolean {
        val state = mutableState.value
        if (state.screen != AppScreen.Result && trigger != ReviewTrigger.ExplicitExit) return false
        return !state.busy &&
            state.accountDeletionConfirmation == null &&
            state.accountDeletionCleanup == null &&
            state.invitation == null &&
            !state.cameraExplanationVisible &&
            !state.voiceExplanationVisible &&
            state.voice.state in setOf(VoiceRoomState.Idle, VoiceRoomState.Unavailable, VoiceRoomState.Failed) &&
            state.voiceConsent.state in setOf(VoiceConsentState.Idle, VoiceConsentState.Ended, VoiceConsentState.Unavailable)
    }

    private fun isOlderSessionRevision(
        candidate: GameSessionSnapshot,
        current: GameSessionSnapshot,
    ): Boolean = when {
        candidate.revision > 0 && current.revision > 0 -> candidate.revision < current.revision
        else -> candidate.lastActivityAtUtc < current.lastActivityAtUtc
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

    private suspend fun performAutobusCommand(
        retryAfterRecovery: Boolean = false,
        canRetryAfterRecovery: (GameSessionSnapshot, GameSessionSnapshot) -> Boolean = { attempted, recovered ->
            attempted.isSameAutobusRound(recovered) &&
                recovered.autobus?.phase in setOf("active", "grace")
        },
        command: suspend (GameSessionSnapshot) -> GameSessionSnapshot,
    ): GameSessionSnapshot? {
        val game = requireGame()
        return try {
            command(game)
        } catch (error: ApiException) {
            if (error.code !in AuthoritativeRefreshErrors) throw error
            val recovered = gateway.rejoin(game.sessionId)
            onAuthoritativeSnapshot(recovered, recoveredFromInterruption = true)
            haptics.perform(HapticEvent.Warning)
            if (retryAfterRecovery && canRetryAfterRecovery(game, recovered)) {
                command(recovered)
            } else {
                null
            }
        }
    }

    private fun launchAction(action: suspend () -> Unit) {
        if (actionJob?.isActive == true) return
        actionJob = scope.launch {
            mutableState.update { it.copy(busy = true, errorCode = null) }
            try {
                action()
            } catch (error: ApiException) {
                if (error.code == "session_expired") {
                    resetRecoveryOrchestration()
                    runCatching { voice?.leave() }
                    runCatching { voiceConsent?.end() }
                    runCatching { realtime.stop() }
                    runCatching { gateway.clearLocalSession() }
                    recentGameSessionPreferences.clear()
                    mutableState.value = FamilyGamesUiState(
                        screen = AppScreen.Welcome,
                        language = mutableState.value.language,
                        errorCode = error.code,
                    )
                } else {
                    mutableState.update { it.copy(errorCode = error.code) }
                }
                haptics.perform(HapticEvent.Error)
            } catch (error: Throwable) {
                mutableState.update { it.copy(errorCode = "unexpected_error") }
                haptics.perform(HapticEvent.Error)
            } finally {
                mutableState.update { it.copy(busy = false) }
                requestPendingReviewIfReady()
            }
        }
    }

    private fun commandId(): String = buildString(32) {
        repeat(32) { append("0123456789abcdef"[Random.nextInt(16)]) }
    }

    private fun com.botglobal.lamma.app.data.AutobusSnapshot.acceptsDraftsAt(now: Instant): Boolean {
        val deadlineText = if (phase == "grace") graceEndsAtUtc else roundDeadlineAtUtc
        val deadline = deadlineText?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return true
        val extensionMillis = if (phase == "active") 5_000 else 0
        return now.toEpochMilliseconds() <= deadline.toEpochMilliseconds() + extensionMillis
    }

    private fun GameSessionSnapshot.isSameAutobusRound(other: GameSessionSnapshot): Boolean =
        sessionId == other.sessionId &&
            matchNumber == other.matchNumber &&
            autobus?.currentRound == other.autobus?.currentRound

    private fun GameSessionSnapshot.autobusActionScope(): AutobusActionScope? {
        val state = autobus ?: return null
        val deadline = when (state.phase) {
            "active" -> state.roundDeadlineAtUtc
            "grace" -> state.graceEndsAtUtc
            "reveal" -> state.voteDeadlineAtUtc
            else -> null
        }
        return AutobusActionScope(
            sessionId = sessionId,
            matchNumber = matchNumber,
            round = state.currentRound,
            phase = state.phase,
            revealCategoryKey = state.revealCategoryKey,
            deadlineAtUtc = deadline,
        )
    }

    private companion object {
        val GameScreens = setOf(AppScreen.Lobby, AppScreen.Gameplay, AppScreen.Result)
        val DefaultAutobusCategories = listOf("boy_name", "girl_name", "animal", "plant", "object", "country_city")
        val AuthoritativeRefreshErrors = setOf(
            "stale_version",
            "duplicate_command",
            "concurrent_move",
            "duplicate_or_concurrent_move",
            "concurrent_command",
            "duplicate_or_concurrent_command",
            "game_completed",
        )
        val AuthoritativeUnrecoverableRecoveryErrors = setOf(
            "session_not_found",
            "active_session_not_found",
            "session_not_joinable",
            "not_participant",
            "application_context_invalid",
        )
        val RecoveryRetryBackoff = listOf(0L, 500L, 1_000L, 2_000L)
        const val RecoveryRequestTimeoutMillis = 6_000L
    }
}
