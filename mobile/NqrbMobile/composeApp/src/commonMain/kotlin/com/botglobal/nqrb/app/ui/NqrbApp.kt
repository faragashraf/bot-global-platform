package com.botglobal.nqrb.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.time.Clock
import kotlin.time.Instant
import com.botglobal.mobile.platform.appearance.AppearancePreference
import com.botglobal.mobile.platform.appearance.ResolvedAppearance
import com.botglobal.mobile.platform.calling.CallAudioRoute
import com.botglobal.mobile.platform.calling.CallableParticipant
import com.botglobal.mobile.platform.calling.CallDirection
import com.botglobal.mobile.platform.calling.CallingDirectorySnapshot
import com.botglobal.mobile.platform.calling.CallingDirectoryStatus
import com.botglobal.mobile.platform.calling.CallSessionSnapshot
import com.botglobal.mobile.platform.calling.CallState
import com.botglobal.mobile.platform.calling.CallTerminationReason
import com.botglobal.mobile.platform.calling.CallingParticipantAvailability
import com.botglobal.mobile.platform.calling.CallActivitySnapshot
import com.botglobal.mobile.platform.calling.CallActivityLoadState
import com.botglobal.mobile.platform.calling.CallHistoryDetail
import com.botglobal.mobile.platform.calling.CallHistoryFilter
import com.botglobal.mobile.platform.calling.CallHistoryItem
import com.botglobal.mobile.platform.calling.speakerControlTarget
import com.botglobal.mobile.platform.chat.ChatPlayback
import com.botglobal.mobile.platform.chat.ChatPlaybackPhase
import com.botglobal.mobile.platform.chat.ChatSnapshot
import com.botglobal.mobile.platform.chat.ChatVoiceDraft
import com.botglobal.mobile.platform.identity.FederatedAuthenticationError
import com.botglobal.mobile.platform.identity.FederatedAuthenticationState
import com.botglobal.mobile.platform.localization.ContentDirection
import com.botglobal.mobile.platform.notifications.SemanticNotification
import com.botglobal.mobile.platform.notifications.SemanticNotificationDestination
import com.botglobal.nqrb.app.state.NqrbAppState
import com.botglobal.nqrb.app.state.NqrbContactBookLoadState
import com.botglobal.nqrb.app.state.NqrbContactBookSnapshot
import com.botglobal.nqrb.app.state.NqrbContactInviteAcceptState
import com.botglobal.nqrb.app.state.NqrbContactInviteCreateState
import com.botglobal.nqrb.app.state.NqrbGuestCallInviteCreateState
import com.botglobal.nqrb.app.state.NqrbContactMutationState
import com.botglobal.nqrb.app.state.NqrbBlockState
import com.botglobal.nqrb.app.state.NqrbContactSearchState
import com.botglobal.nqrb.app.state.NqrbDestination
import com.botglobal.nqrb.app.state.NqrbDirectChatEntryState
import com.botglobal.nqrb.app.state.NqrbChatRecordingState
import com.botglobal.nqrb.app.state.NqrbChatVoicePlaybackState
import com.botglobal.nqrb.app.state.NqrbRingtone
import com.botglobal.nqrb.app.state.NqrbStartupState
import com.botglobal.nqrb.app.state.NqrbAccountActionState
import com.botglobal.nqrb.app.state.NqrbAccountProfileState
import com.botglobal.nqrb.app.config.NqrbPublicSite
import com.botglobal.nqrb.app.data.NqrbContactInvite
import com.botglobal.nqrb.app.data.NqrbContact
import com.botglobal.nqrb.resources.Res
import com.botglobal.nqrb.resources.google_g
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import org.jetbrains.compose.resources.painterResource
import kotlin.math.roundToInt

internal enum class NqrbDeletionConfirmation { Closed, Explanation, Final }

internal data class NqrbPublicLink(val label: String, val url: String)

internal fun nqrbPublicLinks(strings: NqrbStrings): List<NqrbPublicLink> = listOf(
    NqrbPublicLink(strings.privacyPolicy, NqrbPublicSite.PrivacyPolicyUrl),
    NqrbPublicLink(strings.accountDeletionHelp, NqrbPublicSite.AccountDeletionUrl),
    NqrbPublicLink(strings.support, NqrbPublicSite.SupportUrl),
)

@Composable
fun NqrbApp(
    appState: NqrbAppState = remember { NqrbAppState() },
    onResolvedAppearanceChanged: (ResolvedAppearance) -> Unit = {},
    onShareInvite: (String) -> Boolean = { false },
    onPreviewRingtone: (NqrbRingtone) -> Unit = {},
    onChoosePhoneRingtone: () -> Unit = {},
    onNotificationPermissionNeeded: () -> Unit = {},
    notificationsEnabled: Boolean = true,
    onOpenNotificationSettings: () -> Unit = {},
    onOpenStoreDestination: (String) -> Unit = {},
    callTime: (String, String) -> NqrbCallTime = ::basicCallTime,
) {
    val locale by appState.locale.state.collectAsState()
    val appearance by appState.appearance.state.collectAsState()
    val backStack by appState.navigation.backStack.collectAsState()
    val authentication by appState.identity.state.collectAsState()
    val startupState by appState.startupState.collectAsState()
    val contactBook by appState.contactBook.state.collectAsState()
    val chat by appState.chat.state.collectAsState()
    val chatRecordingState by appState.chatRecordingState.collectAsState()
    val chatVoiceDraft by appState.chatVoiceDraft.collectAsState()
    val chatVoicePlayback by appState.chatVoicePlayback.collectAsState()
    val chatPlayback by appState.chatVoicePlayer.state.collectAsState()
    val directChatEntryState by appState.directChatEntryState.collectAsState()
    val call by appState.calling.state.collectAsState()
    val callingDirectory by appState.callingDirectory.state.collectAsState()
    val callActivity by appState.callActivity.state.collectAsState()
    val notifications by appState.notifications.collectAsState()
    val missedCallBadgeCount by appState.missedCallBadgeCount.collectAsState()
    val requiredUpdateMessage by appState.requiredUpdateMessage.collectAsState()
    val requiredUpdateDestination by appState.requiredUpdateDestination.collectAsState()
    val microphoneExplanation by appState.microphoneExplanationVisible.collectAsState()
    val microphoneBlocked by appState.microphonePermissionBlocked.collectAsState()
    var minimizedCallId by remember { mutableStateOf<String?>(null) }
    val callId = call.callId?.value
    val isCallMinimized = callId != null && minimizedCallId == callId && call.state in VisibleCallStates
    val systemIsDark = isSystemInDarkTheme()
    val strings = nqrbStrings(locale.languageTag)
    val layoutDirection = if (locale.direction == ContentDirection.RightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr

    LaunchedEffect(systemIsDark) {
        appState.appearance.updateSystemAppearance(systemIsDark)
    }
    LaunchedEffect(appState) {
        appState.startup()
    }
    LaunchedEffect(appState) {
        appState.showCurrentCallRequests.collect { minimizedCallId = null }
    }
    LaunchedEffect(authentication) {
        if (authentication is FederatedAuthenticationState.SignedIn) {
            onNotificationPermissionNeeded()
        }
    }
    SideEffect {
        onResolvedAppearanceChanged(appearance.resolved)
    }

    NqrbTheme(appearance.resolved) {
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
            NqrbSystemBackHandler(
                enabled = (call.state in VisibleCallStates && !isCallMinimized && canMinimizeCall(call.state)) ||
                    backStack.size > 1 || directChatEntryState is NqrbDirectChatEntryState.Opening,
                onBack = {
                    if (call.state in VisibleCallStates && !isCallMinimized && canMinimizeCall(call.state)) {
                        minimizedCallId = callId
                    } else {
                        appState.handleSystemBack()
                    }
                },
            )
            NqrbShell(
                destination = backStack.last(),
                strings = strings,
                languageTag = locale.languageTag,
                appState = appState,
                layoutDirection = layoutDirection,
                authentication = authentication,
                startupState = startupState,
                contactBook = contactBook,
                chat = chat,
                chatRecordingState = chatRecordingState,
                chatVoiceDraft = chatVoiceDraft,
                chatVoicePlayback = chatVoicePlayback,
                chatPlayback = chatPlayback,
                call = call,
                isCallMinimized = isCallMinimized,
                onMinimizeCall = { minimizedCallId = callId },
                onRestoreCall = { minimizedCallId = null },
                callingDirectory = callingDirectory,
                callActivity = callActivity,
                notifications = notifications,
                missedCallBadgeCount = missedCallBadgeCount,
                requiredUpdateMessage = requiredUpdateMessage,
                requiredUpdateDestination = requiredUpdateDestination,
                microphoneExplanation = microphoneExplanation,
                microphoneBlocked = microphoneBlocked,
                onShareInvite = onShareInvite,
                onPreviewRingtone = onPreviewRingtone,
                onChoosePhoneRingtone = onChoosePhoneRingtone,
                notificationsEnabled = notificationsEnabled,
                onOpenNotificationSettings = onOpenNotificationSettings,
                onOpenStoreDestination = onOpenStoreDestination,
                callTime = callTime,
            )
        }
    }
}

@Composable
private fun NqrbShell(
    destination: NqrbDestination,
    strings: NqrbStrings,
    languageTag: String,
    appState: NqrbAppState,
    layoutDirection: LayoutDirection,
    authentication: FederatedAuthenticationState,
    startupState: NqrbStartupState,
    contactBook: NqrbContactBookSnapshot,
    chat: ChatSnapshot,
    chatRecordingState: NqrbChatRecordingState,
    chatVoiceDraft: ChatVoiceDraft?,
    chatVoicePlayback: NqrbChatVoicePlaybackState?,
    chatPlayback: ChatPlayback,
    call: CallSessionSnapshot,
    isCallMinimized: Boolean,
    onMinimizeCall: () -> Unit,
    onRestoreCall: () -> Unit,
    callingDirectory: CallingDirectorySnapshot,
    callActivity: CallActivitySnapshot,
    notifications: List<SemanticNotification>,
    missedCallBadgeCount: Int,
    requiredUpdateMessage: String?,
    requiredUpdateDestination: String?,
    microphoneExplanation: Boolean,
    microphoneBlocked: Boolean,
    onShareInvite: (String) -> Boolean,
    onPreviewRingtone: (NqrbRingtone) -> Unit,
    onChoosePhoneRingtone: () -> Unit,
    notificationsEnabled: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onOpenStoreDestination: (String) -> Unit,
    callTime: (String, String) -> NqrbCallTime,
) {
    val colors = LocalNqrbColors.current
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            if (!microphoneExplanation && (isCallMinimized || chatVoicePlayback != null)) {
                Column(Modifier.statusBarsPadding()) {
                    if (isCallMinimized) {
                        CompactCallBar(strings, call, contactBook, onRestoreCall) {
                            appState.setCallMuted(!call.media.muted)
                        }
                    }
                    chatVoicePlayback?.let {
                        CompactChatVoiceBar(
                            strings = nqrbChatStrings(languageTag),
                            playbackState = it,
                            playback = chatPlayback,
                            onToggle = appState::toggleCurrentChatVoicePlayback,
                            onStop = appState::stopChatPlayback,
                            onOpen = appState::openCurrentChatVoiceConversation,
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (destination in NQRB_TOP_LEVEL_DESTINATIONS &&
                (call.state !in VisibleCallStates || isCallMinimized) && !microphoneExplanation
            ) {
                NqrbBottomBar(
                    current = destination,
                    strings = strings,
                    notifications = notifications,
                    missedCallBadgeCount = missedCallBadgeCount,
                    onSelect = appState::selectTopLevel,
                )
            }
        },
    ) { contentPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(colors.backgroundGlow, colors.background, colors.background),
                    ),
                )
                .padding(contentPadding)
                .consumeWindowInsets(contentPadding),
        ) {
            when {
                microphoneExplanation -> MicrophoneExplanationScreen(strings, appState)
                call.state in VisibleCallStates && !isCallMinimized ->
                    InCallScreen(strings, call, contactBook, appState, onMinimizeCall)
                showsRestoringSession(startupState, call.state) -> RestoringSessionScreen(strings)
                else -> AnimatedContent(destination) { current -> when (current) {
                    NqrbDestination.SignIn -> if (showsGoogleSignIn(startupState, current)) {
                        SignInScreen(strings, authentication, appState)
                    } else {
                        RestoringSessionScreen(strings)
                    }
                    NqrbDestination.RequiredUpdate -> RequiredUpdateScreen(
                        strings = strings,
                        message = requiredUpdateMessage,
                        storeDestination = requiredUpdateDestination,
                        onOpenStoreDestination = onOpenStoreDestination,
                    )
                    NqrbDestination.Home -> HomeScreen(
                        strings,
                        nqrbChatStrings(languageTag),
                        appState,
                        callingDirectory,
                        contactBook,
                        chat,
                        microphoneBlocked,
                    )
                    NqrbDestination.Settings -> SettingsScreen(
                        strings = strings,
                        languageTag = languageTag,
                        appState = appState,
                        layoutDirection = layoutDirection,
                        activity = callActivity,
                        onPreviewRingtone = onPreviewRingtone,
                        onChoosePhoneRingtone = onChoosePhoneRingtone,
                        notificationsEnabled = notificationsEnabled,
                        onOpenNotificationSettings = onOpenNotificationSettings,
                        callTime = callTime,
                    )
                    NqrbDestination.History -> CallHistoryScreen(strings, languageTag, appState, callActivity, contactBook, callingDirectory, callTime)
                    NqrbDestination.Notifications -> NotificationsScreen(strings, languageTag, appState, notifications)
                    NqrbDestination.People -> PeopleScreen(strings, languageTag, contactBook, callingDirectory, chat, appState, onShareInvite, callTime)
                    NqrbDestination.Profile -> ProfileScreen(strings, appState, contactBook)
                    NqrbDestination.Chats -> NqrbChatListScreen(languageTag, chat, appState, callTime)
                    NqrbDestination.ChatThread -> NqrbChatThreadScreen(languageTag, chat, chatRecordingState, chatVoiceDraft, appState, callTime)
                } }
            }
            if (destination in setOf(NqrbDestination.Home, NqrbDestination.People) &&
                !microphoneExplanation && (call.state !in VisibleCallStates || isCallMinimized)
            ) {
                DirectChatEntryFeedback(
                    strings = nqrbChatStrings(languageTag),
                    appState = appState,
                    modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = NqrbSpacing.Lg, vertical = NqrbSpacing.Sm),
                )
            }
        }
    }
}

@Composable
private fun CompactChatVoiceBar(
    strings: NqrbChatStrings,
    playbackState: NqrbChatVoicePlaybackState,
    playback: ChatPlayback,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
) {
    val colors = LocalNqrbColors.current
    val active = playback.takeIf { it.key == playbackState.key } ?: ChatPlayback()
    val playing = active.phase == ChatPlaybackPhase.Playing
    val progress = if (playbackState.durationMilliseconds > 0)
        (active.elapsedMilliseconds.toFloat() / playbackState.durationMilliseconds).coerceIn(0f, 1f) else 0f
    val stateLabel = when (active.phase) {
        ChatPlaybackPhase.Preparing -> strings.preparing
        ChatPlaybackPhase.Playing -> strings.playing
        ChatPlaybackPhase.Paused -> strings.paused
        ChatPlaybackPhase.Completed -> strings.completed
        ChatPlaybackPhase.Failed -> strings.failure(NqrbChatRecordingState.PlaybackUnavailable)
        else -> strings.voiceNote
    }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        color = colors.elevatedSurface,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = NqrbSpacing.Md, vertical = NqrbSpacing.Sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm)) {
                Box(Modifier.size(38.dp).background(colors.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
                    NqrbIcon(NqrbGlyph.Microphone, null, colors.accent, Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(playbackState.title.ifBlank { strings.voiceNote }, style = MaterialTheme.typography.labelLarge, color = colors.textPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(
                            stateLabel,
                            strings.duration(if (active.elapsedMilliseconds > 0) active.elapsedMilliseconds else playbackState.durationMilliseconds),
                            if (playbackState.queueTotal > 1) "${playbackState.queuePosition}/${playbackState.queueTotal}" else null,
                        ).filterNotNull().joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = onToggle,
                    enabled = active.phase in setOf(ChatPlaybackPhase.Playing, ChatPlaybackPhase.Paused),
                    modifier = Modifier.size(44.dp).semantics { contentDescription = if (playing) strings.pause else strings.play },
                ) {
                    NqrbIcon(if (playing) NqrbGlyph.Pause else NqrbGlyph.Play, null,
                        if (active.phase == ChatPlaybackPhase.Preparing) colors.disabledContent else colors.accent, Modifier.size(23.dp))
                }
                IconButton(onClick = onStop, modifier = Modifier.size(44.dp).semantics { contentDescription = strings.playbackStop }) {
                    NqrbIcon(NqrbGlyph.Close, null, colors.textSecondary, Modifier.size(22.dp))
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(3.dp).padding(top = 2.dp),
                color = colors.accent,
                trackColor = colors.border,
            )
        }
    }
}

@Composable
private fun RestoringSessionScreen(strings: NqrbStrings) {
    NqrbWelcomeUi(strings = strings, loading = true)
}

@Composable
private fun RequiredUpdateScreen(
    strings: NqrbStrings,
    message: String?,
    storeDestination: String?,
    onOpenStoreDestination: (String) -> Unit,
) {
    val colors = LocalNqrbColors.current
    BrandedFlowFrame(strings, {}) {
        FlowHero(
            NqrbGlyph.Notifications,
            strings.updateRequiredTitle,
            message?.takeIf(String::isNotBlank) ?: strings.updateRequiredBody,
        )
        Button(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NqrbSpacing.Lg),
            enabled = !storeDestination.isNullOrBlank(),
            onClick = { storeDestination?.let(onOpenStoreDestination) },
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = Color.White,
            ),
        ) {
            Text(strings.updateNow)
        }
    }
}

private val NQRB_TOP_LEVEL_DESTINATIONS = NqrbAppState.TOP_LEVEL_DESTINATIONS
private val VisibleCallStates = setOf(
    CallState.Preparing, CallState.Connecting, CallState.Ringing, CallState.Answering, CallState.Active,
    CallState.Reconnecting, CallState.Ending, CallState.Rejected, CallState.Cancelled, CallState.Missed,
    CallState.Expired, CallState.Ended, CallState.Failed,
)

internal fun canMinimizeCall(state: CallState): Boolean =
    state == CallState.Active || state == CallState.Reconnecting


internal fun showsRestoringSession(startupState: NqrbStartupState, callState: CallState): Boolean =
    startupState == NqrbStartupState.RestoringSession && callState !in VisibleCallStates

internal fun showsGoogleSignIn(startupState: NqrbStartupState, destination: NqrbDestination): Boolean =
    startupState == NqrbStartupState.Ready && destination == NqrbDestination.SignIn

@Composable
private fun SignInScreen(
    strings: NqrbStrings,
    authentication: FederatedAuthenticationState,
    appState: NqrbAppState,
) {
    val scope = rememberCoroutineScope()
    val busy = authentication is FederatedAuthenticationState.SigningIn ||
        authentication is FederatedAuthenticationState.RestoringSession
    NqrbWelcomeUi(
        strings = strings,
        loading = false,
        onSettings = appState::openSettings,
        title = strings.signInTitle,
        body = strings.signInBody,
    ) {
        Button(
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
            enabled = !busy,
            onClick = { scope.launch { appState.signInWithGoogle() } },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFF2F2F2),
                contentColor = Color(0xFF1F1F1F),
            ),
        ) {
            Image(
                painter = painterResource(Res.drawable.google_g),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.width(10.dp))
            Text(strings.continueWithGoogle)
        }
        if (authentication is FederatedAuthenticationState.AuthenticationError) {
            val message = when (authentication.reason) {
                FederatedAuthenticationError.ConfigurationMissing -> strings.googleConfigurationMissing
                FederatedAuthenticationError.ProviderUnavailable -> strings.googleUnavailable
                FederatedAuthenticationError.ProviderFailure -> strings.googleSignInFailed
                FederatedAuthenticationError.BackendRejected -> strings.googleRejected
                FederatedAuthenticationError.AccountLinkRequired -> strings.accountLinkRequired
                FederatedAuthenticationError.NetworkFailure -> strings.networkFailure
                FederatedAuthenticationError.AuthenticationFailure -> strings.googleSignInFailed
            }
            InfoNote(message)
        }
        InfoNote(strings.signInPrivacy)
    }
}

@Composable
private fun BrandedFlowFrame(
    strings: NqrbStrings,
    onSettings: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NqrbSpacing.Lg, vertical = NqrbSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Md),
    ) {
        ProductHeader(strings, onSettings)
        Spacer(Modifier.height(NqrbSpacing.Sm))
        content()
        Spacer(Modifier.height(NqrbSpacing.Lg))
    }
}

@Composable
private fun FlowHero(glyph: NqrbGlyph, title: String, body: String) {
    val colors = LocalNqrbColors.current
    Surface(
        Modifier.fillMaxWidth(),
        color = colors.surface,
        shape = RoundedCornerShape(30.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(NqrbSpacing.Xl), verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Md)) {
            Box(
                Modifier.size(58.dp).clip(CircleShape).background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                NqrbIcon(glyph, title, colors.accent, Modifier.size(30.dp))
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
            Text(body, style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
        }
    }
}

@Composable
private fun InfoNote(text: String) {
    val colors = LocalNqrbColors.current
    Surface(Modifier.fillMaxWidth(), color = colors.accentSoft, shape = RoundedCornerShape(18.dp)) {
        Text(
            text,
            Modifier.padding(NqrbSpacing.Md),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun NqrbListSkeleton(label: String, rows: Int = 3) {
    val colors = LocalNqrbColors.current
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm),
    ) {
        repeat(rows) { index ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.elevatedSurface)
                    .padding(NqrbSpacing.Md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Md),
            ) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(colors.accentSoft))
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(if (index % 2 == 0) .56f else .68f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.border),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(.38f)
                            .height(9.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(colors.border.copy(alpha = .7f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun BackgroundRefreshIndicator(visible: Boolean) {
    if (!visible) return
    val colors = LocalNqrbColors.current
    LinearProgressIndicator(
        modifier = Modifier.fillMaxWidth().height(2.dp),
        color = colors.accent,
        trackColor = colors.accentSoft,
    )
}

@Composable
private fun DirectChatEntryFeedback(strings: NqrbChatStrings, appState: NqrbAppState, modifier: Modifier = Modifier) {
    val state by appState.directChatEntryState.collectAsState()
    if (state == NqrbDirectChatEntryState.Idle) return
    val colors = LocalNqrbColors.current
    Surface(
        modifier = modifier.widthIn(max = NqrbLayout.ThreadMaxWidth).fillMaxWidth().semantics {
            liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
        },
        color = colors.elevatedSurface,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.padding(horizontal = NqrbSpacing.Md, vertical = NqrbSpacing.Sm).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm),
        ) {
            if (state is NqrbDirectChatEntryState.Opening) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.accent)
            } else {
                NqrbIcon(NqrbGlyph.Chat, null, colors.textSecondary, Modifier.size(22.dp))
            }
            Text(
                if (state is NqrbDirectChatEntryState.Opening) strings.openingConversation else strings.conversationOpenFailed,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun PeopleScreen(
    strings: NqrbStrings,
    languageTag: String,
    contactBook: NqrbContactBookSnapshot,
    directory: CallingDirectorySnapshot,
    chat: ChatSnapshot,
    appState: NqrbAppState,
    onShareInvite: (String) -> Boolean,
    callTime: (String, String) -> NqrbCallTime,
) {
    val colors = LocalNqrbColors.current
    val chatStrings = nqrbChatStrings(languageTag)
    val inviteRequester = remember { BringIntoViewRequester() }
    var query by remember { mutableStateOf(contactBook.searchQuery) }
    var editingContact by remember { mutableStateOf<NqrbContact?>(null) }
    var nicknameDraft by remember { mutableStateOf("") }
    var nicknameSaving by remember { mutableStateOf(false) }
    var nicknameSaveFailed by remember { mutableStateOf(false) }
    var blockingMembershipId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun closeNicknameEditor() {
        editingContact = null
        nicknameDraft = ""
        nicknameSaveFailed = false
    }
    fun closeBlockConfirmation() {
        blockingMembershipId = null
    }
    LaunchedEffect(editingContact, nicknameSaving) {
        appState.setReviewWorkflowActive("people-nickname-edit", editingContact != null || nicknameSaving)
    }
    LaunchedEffect(blockingMembershipId) {
        appState.setReviewWorkflowActive("people-block-contact", blockingMembershipId != null)
    }
    DisposableEffect(Unit) {
        onDispose {
            appState.setReviewWorkflowActive("people-nickname-edit", false)
            appState.setReviewWorkflowActive("people-block-contact", false)
        }
    }
    LaunchedEffect(query, appState) {
        delay(350)
        appState.searchNqrbUsers(query)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NqrbSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Md),
    ) {
        ProductHeader(strings, appState::openSettings)
        TextButton(onClick = { scope.launch { inviteRequester.bringIntoView() } }) {
            NqrbIcon(NqrbGlyph.Link, strings.shareInvite, colors.accent, Modifier.size(20.dp))
            Spacer(Modifier.width(NqrbSpacing.Sm))
            Text(strings.shareInvite)
        }
        Text(strings.searchNqrbUsers, style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text(strings.searchPlaceholder) },
            leadingIcon = { NqrbIcon(NqrbGlyph.Search, strings.searchNqrbUsers, colors.textSecondary, Modifier.size(20.dp)) },
            trailingIcon = if (query.isNotBlank()) ({
                IconButton(onClick = { query = "" }) {
                    NqrbIcon(NqrbGlyph.Close, strings.clearSearch, colors.textSecondary, Modifier.size(18.dp))
                }
            }) else null,
        )
        when (contactBook.searchState) {
            NqrbContactSearchState.Idle -> Unit
            NqrbContactSearchState.TooShort -> if (query.isNotBlank()) InfoNote(strings.contactSearchTooShort)
            NqrbContactSearchState.Loading -> NqrbListSkeleton(strings.callingDirectoryLoading, rows = 2)
            NqrbContactSearchState.Empty -> InfoNote(strings.contactSearchEmpty)
            NqrbContactSearchState.Error -> InfoNote(strings.contactSearchError)
            NqrbContactSearchState.Ready -> contactBook.searchResults.forEach { contact ->
                val alreadySaved = contactBook.contacts.any { it.membershipId == contact.membershipId }
                val isBlocked = contactBook.blockedAccounts.any { it.membershipId == contact.membershipId }
                NqrbServerContactCard(
                    displayName = contact.displayName,
                    statusLabel = if (isBlocked) strings.blockedLabel else null,
                    actionLabel = if (isBlocked) strings.blockedLabel else if (alreadySaved) strings.selected else strings.addContact,
                    actionEnabled = !isBlocked && !alreadySaved && contactBook.mutationState != NqrbContactMutationState.Saving,
                    onAction = { appState.addNqrbContact(contact.membershipId) },
                )
            }
        }
        if (contactBook.searchHasMore) {
            Button(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = !contactBook.searchLoadingMore,
                onClick = appState::loadMoreNqrbSearchResults,
            ) {
                Text(if (contactBook.searchLoadingMore) strings.callingDirectoryLoading else strings.loadMoreSearchResults)
            }
        }
        Text(strings.savedContactsTitle, style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
        Text(strings.savedContactsBody, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        BackgroundRefreshIndicator(contactBook.contactsRefreshing)
        InfoNote(strings.swipeToCallHint)
        if (contactBook.contactsRefreshFailed) {
            InfoNote(strings.savedContactsError)
            DirectoryRefreshAction(strings.retry, appState::refreshContactBook)
        }
        when (contactBook.contactsState) {
            NqrbContactBookLoadState.Idle,
            NqrbContactBookLoadState.Loading,
            -> NqrbListSkeleton(strings.savedContactsLoading)
            NqrbContactBookLoadState.Empty -> InfoNote(strings.savedContactsEmpty)
            NqrbContactBookLoadState.Error -> {
                InfoNote(strings.savedContactsError)
                DirectoryRefreshAction(strings.retry, appState::refreshContactBook)
            }
            NqrbContactBookLoadState.Ready -> contactBook.contacts.forEach { contact ->
                val participant = directory.participants.firstOrNull { it.membershipId == contact.membershipId }
                val isBlocked = contactBook.blockedAccounts.any { it.membershipId == contact.membershipId }
                NqrbServerContactCard(
                    displayName = contact.nickname ?: contact.displayName,
                    nameUnreadCount = chat.unreadCountForCounterpart(contact.membershipId),
                    nameUnreadLabel = chatStrings.unreadCount(chat.unreadCountForCounterpart(contact.membershipId)),
                    secondaryLabel = contact.nickname?.let { strings.nicknameOriginal.replace("%s", contact.displayName) },
                    statusLabel = if (isBlocked) strings.blockedLabel else
                        participant?.let { nqrbDirectoryAvailability(strings, it) } ?: strings.availabilityUnverified,
                    actionLabel = strings.removeContact,
                    actionEnabled = contactBook.mutationState != NqrbContactMutationState.Removing,
                    onAction = { appState.removeNqrbContact(contact.membershipId) },
                    callLabel = strings.call,
                    callEnabled = !isBlocked && participant != null,
                    onCall = { participant?.let(appState::requestOutgoingCall) },
                    messageLabel = nqrbChatStrings(languageTag).chats,
                    messageEnabled = !isBlocked,
                    messageUnreadCount = chat.unreadCountForCounterpart(contact.membershipId),
                    messageUnreadLabel = chatStrings.unreadCount(chat.unreadCountForCounterpart(contact.membershipId)),
                    onMessage = { appState.openChatWith(contact.membershipId) },
                    nameActionLabel = chatStrings.openChatWith(contact.nickname ?: contact.displayName),
                    nameActionEnabled = !isBlocked,
                    onNameClick = { appState.openChatWith(contact.membershipId) },
                    overflowLabel = strings.contactActions,
                    editNicknameLabel = strings.editContactNickname,
                    onEditNickname = {
                        editingContact = contact
                        nicknameDraft = contact.nickname.orEmpty()
                        nicknameSaveFailed = false
                    },
                    blockLabel = if (isBlocked) strings.unblockContact else strings.blockContact,
                    onBlock = {
                        if (isBlocked) appState.unblockNqrbAccount(contact.membershipId)
                        else blockingMembershipId = contact.membershipId
                    },
                )
            }
        }
        if (contactBook.contactsHasMore) {
            Button(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = !contactBook.contactsLoadingMore,
                onClick = appState::loadMoreNqrbContacts,
            ) {
                Text(if (contactBook.contactsLoadingMore) strings.savedContactsLoading else strings.loadMoreContacts)
            }
        }
        if (contactBook.contactsState == NqrbContactBookLoadState.Ready &&
            directory.status != CallingDirectoryStatus.Error
        ) {
            DirectoryRefreshAction(strings.refreshCallingDirectory, appState::refreshCallingDirectory)
        }
        if (contactBook.blockedAccounts.isNotEmpty()) {
            Text(strings.blockedTitle, style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
            Text(strings.blockedBody, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            contactBook.blockedAccounts.forEach { blocked ->
                NqrbServerContactCard(
                    displayName = blocked.displayName,
                    statusLabel = strings.blockedLabel,
                    actionLabel = strings.unblockContact,
                    actionEnabled = contactBook.blockState != NqrbBlockState.Working,
                    onAction = { appState.unblockNqrbAccount(blocked.membershipId) },
                )
            }
        }
        if (contactBook.blockState == NqrbBlockState.Error) {
            InfoNote(if (contactBook.blockErrorIsLoad) strings.blockedLoadError else strings.blockError)
            DirectoryRefreshAction(strings.retry, appState::refreshBlockedAccounts)
        }
        NqrbInviteSection(strings, contactBook, appState, onShareInvite,
            modifier = Modifier.bringIntoViewRequester(inviteRequester)) { utc ->
            callTime(utc, languageTag).fullLabel
        }
    }
    editingContact?.let { contact ->
        AlertDialog(
            onDismissRequest = { if (!nicknameSaving) closeNicknameEditor() },
            title = { Text(strings.editContactNickname) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm)) {
                    Text(strings.nicknameOriginal.replace("%s", contact.displayName))
                    OutlinedTextField(
                        value = nicknameDraft,
                        onValueChange = { nicknameDraft = it.take(80); nicknameSaveFailed = false },
                        label = { Text(strings.nicknameHint) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (nicknameSaveFailed) Text(strings.nicknameError, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !nicknameSaving,
                    onClick = {
                        nicknameSaving = true
                        scope.launch {
                            val saved = appState.updateNqrbContactNickname(contact.membershipId, nicknameDraft.trim().ifBlank { null })
                            nicknameSaving = false
                            if (saved) closeNicknameEditor() else nicknameSaveFailed = true
                        }
                    },
                ) { Text(strings.saveNickname) }
            },
            dismissButton = {
                TextButton(enabled = !nicknameSaving, onClick = { closeNicknameEditor() }) { Text(strings.cancel) }
            },
        )
    }
    blockingMembershipId?.let { membershipId ->
        AlertDialog(
            onDismissRequest = { closeBlockConfirmation() },
            title = { Text(strings.blockConfirmTitle) },
            text = { Text(strings.blockConfirmBody) },
            confirmButton = { TextButton(onClick = {
                closeBlockConfirmation()
                appState.blockNqrbAccount(membershipId)
            }) { Text(strings.blockContact) } },
            dismissButton = { TextButton(onClick = { closeBlockConfirmation() }) { Text(strings.cancel) } },
        )
    }
}

@Composable
private fun NqrbInviteSection(
    strings: NqrbStrings,
    contactBook: NqrbContactBookSnapshot,
    appState: NqrbAppState,
    onShareInvite: (String) -> Boolean,
    modifier: Modifier = Modifier,
    formatExpiry: (String) -> String,
) {
    val colors = LocalNqrbColors.current
    val clipboard = LocalClipboardManager.current
    var copied by remember(contactBook.createdInvite?.shareLink) { mutableStateOf(false) }
    var guestCallCopied by remember(contactBook.createdGuestCallInvite?.shareLink) { mutableStateOf(false) }
    var shareAfterCreate by remember { mutableStateOf(false) }
    var shareUnavailable by remember { mutableStateOf(false) }
    var guestCallShareUnavailable by remember { mutableStateOf(false) }
    var showAccept by remember { mutableStateOf(false) }
    var currentTime by remember { mutableStateOf(Clock.System.now()) }
    val activeGuestInvite = contactBook.createdGuestCallInvite?.takeUnless {
        isGuestCallInviteExpired(it.expiresAtUtc, currentTime)
    }
    LaunchedEffect(contactBook.createdGuestCallInvite?.expiresAtUtc) {
        if (contactBook.createdGuestCallInvite != null) {
            while (true) {
                currentTime = Clock.System.now()
                delay(15_000)
            }
        }
    }
    LaunchedEffect(contactBook.inviteCreateState, contactBook.createdInvite) {
        if (shareAfterCreate && contactBook.inviteCreateState == NqrbContactInviteCreateState.Ready) {
            contactBook.createdInvite?.let { invite ->
                shareUnavailable = !onShareInvite(inviteShareMessage(strings, invite))
                shareAfterCreate = false
            }
        } else if (contactBook.inviteCreateState == NqrbContactInviteCreateState.Error) {
            shareAfterCreate = false
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Md)) {
        Surface(
            Modifier.fillMaxWidth(),
            color = colors.accentSoft,
            shape = RoundedCornerShape(16.dp),
        ) {
        Column(Modifier.padding(NqrbSpacing.Lg), verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm)) {
                NqrbIcon(NqrbGlyph.Link, strings.inviteContactsTitle, colors.accent, Modifier.size(24.dp))
                Text(strings.inviteContactsTitle, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            }
            Text(strings.inviteContactsBody, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            Button(
                modifier = Modifier.fillMaxWidth().height(54.dp),
                enabled = contactBook.inviteCreateState != NqrbContactInviteCreateState.Creating,
                onClick = {
                    shareUnavailable = false
                    shareAfterCreate = true
                    appState.createNqrbInvite()
                },
            ) {
                Text(
                    if (contactBook.inviteCreateState == NqrbContactInviteCreateState.Creating) {
                        strings.creatingInvite
                    } else {
                        strings.shareInvite
                    },
                )
            }
            contactBook.createdInvite?.let { invite ->
                TextButton(
                    onClick = appState::toggleAccountInviteDetails,
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "${if (contactBook.accountInviteDetailsVisible) strings.hideDetails else strings.showDetails} ${strings.inviteContactsTitle}"
                    },
                ) { Text(if (contactBook.accountInviteDetailsVisible) strings.hideDetails else strings.showDetails) }
                if (contactBook.accountInviteDetailsVisible) {
                    InviteValueRow(strings.inviteCode, invite.code)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(invite.code))
                            copied = true
                        }) { Text(strings.copyInviteCode) }
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(inviteShareMessage(strings, invite)))
                            copied = true
                        }) { Text(strings.copyInvite) }
                    }
                    if (copied) InfoNote(strings.inviteCopied)
                    if (shareUnavailable) InfoNote(strings.shareInviteUnavailable)
                }
            }
            if (contactBook.inviteCreateState == NqrbContactInviteCreateState.Error) {
                InfoNote(strings.inviteError)
            }
        }
        }
        Surface(
            Modifier.fillMaxWidth(),
            color = colors.surface,
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(NqrbSpacing.Lg), verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm)) {
            Column(verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm)) {
                    NqrbIcon(NqrbGlyph.Microphone, strings.guestCallLinkTitle, colors.accent, Modifier.size(22.dp))
                    Text(strings.guestCallLinkTitle, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                }
                Text(strings.guestCallLinkBody, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                if (activeGuestInvite == null) {
                    Button(
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        enabled = contactBook.guestCallInviteCreateState !in setOf(
                            NqrbGuestCallInviteCreateState.Creating,
                            NqrbGuestCallInviteCreateState.Revoking,
                        ),
                        onClick = {
                            guestCallShareUnavailable = false
                            appState.createNqrbGuestCallInvite()
                        },
                    ) {
                        Text(
                            if (contactBook.guestCallInviteCreateState == NqrbGuestCallInviteCreateState.Creating) {
                                strings.creatingInvite
                            } else {
                                strings.guestCallCreate
                            },
                        )
                    }
                }
                contactBook.createdGuestCallInvite?.let { invite ->
                    if (isGuestCallInviteExpired(invite.expiresAtUtc, currentTime)) {
                        InfoNote(strings.guestCallExpired)
                    } else {
                        InfoNote(strings.guestCallLinkReady)
                        TextButton(
                            onClick = appState::toggleGuestCallDetails,
                            modifier = Modifier.fillMaxWidth().semantics {
                                contentDescription = "${if (contactBook.guestCallDetailsVisible) strings.hideDetails else strings.showDetails} ${strings.guestCallLinkTitle}"
                            },
                        ) { Text(if (contactBook.guestCallDetailsVisible) strings.hideDetails else strings.showDetails) }
                        if (contactBook.guestCallDetailsVisible) {
                            GuestCallWaitingPanel(
                                strings = strings,
                                expiresAtLabel = formatExpiry(invite.expiresAtUtc),
                                revoking = contactBook.guestCallInviteCreateState == NqrbGuestCallInviteCreateState.Revoking,
                                onCopy = {
                                    clipboard.setText(AnnotatedString(invite.shareLink))
                                    guestCallCopied = true
                                },
                                onShare = { guestCallShareUnavailable = !onShareInvite(invite.shareLink) },
                                onCancel = appState::revokeNqrbGuestCallInvite,
                            )
                            if (guestCallCopied) InfoNote(strings.inviteCopied)
                            if (guestCallShareUnavailable) InfoNote(strings.guestCallShareUnavailable)
                        }
                    }
                }
                if (contactBook.guestCallInviteCreateState == NqrbGuestCallInviteCreateState.Error) {
                    InfoNote(strings.inviteError)
                }
            }
            }
        }
        Surface(
            Modifier.fillMaxWidth(),
            color = colors.surface,
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(NqrbSpacing.Md), verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm)) {
            TextButton(onClick = { showAccept = !showAccept }) { Text(strings.acceptInviteTitle) }
            if (showAccept || contactBook.inviteCodeInput.isNotBlank() || contactBook.inviteAcceptState != NqrbContactInviteAcceptState.Idle) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = contactBook.inviteCodeInput,
                    onValueChange = appState::updateNqrbInviteCodeInput,
                    singleLine = true,
                    label = { Text(strings.inviteCodePlaceholder) },
                )
                Button(
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    enabled = contactBook.inviteCodeInput.isNotBlank() &&
                        contactBook.inviteAcceptState !in setOf(
                            NqrbContactInviteAcceptState.Previewing,
                            NqrbContactInviteAcceptState.Accepting,
                        ),
                    onClick = appState::previewNqrbInvite,
                ) { Text(strings.previewInvite) }
                InviteAcceptStatus(strings, contactBook, appState)
            }
        }
    }
    }
}

internal fun isGuestCallInviteExpired(expiresAtUtc: String, now: Instant): Boolean =
    runCatching { Instant.parse(expiresAtUtc) <= now }.getOrDefault(true)

internal fun inviteShareMessage(strings: NqrbStrings, invite: NqrbContactInvite): String =
    strings.inviteShareMessage.replaceFirst("%s", invite.code).replaceFirst("%s", invite.shareLink)

@Composable
private fun InviteValueRow(label: String, value: String) {
    val colors = LocalNqrbColors.current
    Column(verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Xs)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
    }
}

@Composable
private fun GuestCallWaitingPanel(
    strings: NqrbStrings,
    expiresAtLabel: String,
    revoking: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalNqrbColors.current
    Column(
        Modifier.fillMaxWidth().padding(top = NqrbSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm),
    ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm)) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
                    NqrbIcon(NqrbGlyph.Microphone, strings.guestCallWaitingTitle, colors.accent, Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Xs)) {
                    Text(strings.guestCallWaitingTitle, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                    Text(
                        strings.guestCallExpiresAt.replace("%s", expiresAtLabel),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )
                }
            }
            Text(strings.guestCallWaitingInstruction, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            Text(strings.guestCallLinkReady, style = MaterialTheme.typography.labelLarge, color = colors.accent)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Xs)) {
                TextButton(modifier = Modifier.weight(1f), onClick = onCopy) { Text(strings.guestCallCopyLink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                TextButton(modifier = Modifier.weight(1f), onClick = onShare) { Text(strings.guestCallShare, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                TextButton(
                    modifier = Modifier.weight(1f),
                    enabled = !revoking,
                    onClick = onCancel,
                ) { Text(strings.cancel, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
    }
}

@Composable
private fun InviteAcceptStatus(
    strings: NqrbStrings,
    contactBook: NqrbContactBookSnapshot,
    appState: NqrbAppState,
) {
    when (contactBook.inviteAcceptState) {
        NqrbContactInviteAcceptState.Idle -> Unit
        NqrbContactInviteAcceptState.Previewing -> InfoNote(strings.callingDirectoryLoading)
        NqrbContactInviteAcceptState.Confirming -> {
            val name = contactBook.invitePreview?.issuerDisplayName.orEmpty()
            InfoNote(strings.invitePreviewBody.replace("%s", name))
            Button(
                modifier = Modifier.fillMaxWidth().height(54.dp),
                onClick = appState::acceptNqrbInvite,
            ) { Text(strings.acceptInvite) }
            TextButton(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                onClick = appState::cancelNqrbInviteConfirmation,
            ) { Text(strings.cancel) }
        }
        NqrbContactInviteAcceptState.Accepting -> InfoNote(strings.creatingInvite)
        NqrbContactInviteAcceptState.Accepted -> InfoNote(strings.inviteAccepted)
        NqrbContactInviteAcceptState.Invalid -> InfoNote(strings.inviteInvalid)
        NqrbContactInviteAcceptState.Expired -> InfoNote(strings.inviteExpired)
        NqrbContactInviteAcceptState.SelfInvite -> InfoNote(strings.inviteSelf)
        NqrbContactInviteAcceptState.AlreadyClaimed -> InfoNote(strings.inviteClaimed)
        NqrbContactInviteAcceptState.Error -> InfoNote(strings.inviteError)
    }
}

@Composable
private fun NqrbServerContactCard(
    displayName: String,
    nameUnreadCount: Int = 0,
    nameUnreadLabel: String = "",
    secondaryLabel: String? = null,
    statusLabel: String? = null,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
    callLabel: String? = null,
    callEnabled: Boolean = false,
    onCall: () -> Unit = {},
    messageLabel: String? = null,
    messageEnabled: Boolean = false,
    messageUnreadCount: Int = 0,
    messageUnreadLabel: String = "",
    onMessage: () -> Unit = {},
    overflowLabel: String? = null,
    editNicknameLabel: String? = null,
    onEditNickname: () -> Unit = {},
    blockLabel: String? = null,
    onBlock: () -> Unit = {},
    nameActionLabel: String? = null,
    nameActionEnabled: Boolean = false,
    onNameClick: () -> Unit = {},
) {
    val colors = LocalNqrbColors.current
    var actionsExpanded by remember { mutableStateOf(false) }
    NqrbSwipeToCallBox(callLabel, callEnabled, onCall) { swipeModifier ->
        Surface(
            swipeModifier,
            color = colors.surface,
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
        ) {
        Row(
            Modifier.padding(horizontal = NqrbSpacing.Sm, vertical = NqrbSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm),
        ) {
            ParticipantAvatar(displayName)
            Column(
                Modifier.weight(1f).heightIn(min = 48.dp).then(
                    if (nameActionLabel != null) Modifier.clickable(enabled = nameActionEnabled, onClick = onNameClick).semantics {
                        role = Role.Button
                        contentDescription = nameActionLabel
                        if (!nameActionEnabled) disabled()
                    } else Modifier
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm)) {
                    Text(
                        displayName,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    NqrbUnreadBadge(nameUnreadCount, nameUnreadLabel)
                }
                secondaryLabel?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                statusLabel?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (callLabel != null) {
                if (messageLabel != null) {
                    Box {
                        IconButton(
                            modifier = Modifier.size(48.dp),
                            enabled = messageEnabled,
                            onClick = onMessage,
                        ) {
                            NqrbIcon(NqrbGlyph.Chat, messageLabel, colors.accent, Modifier.size(22.dp))
                        }
                        NqrbUnreadBadge(
                            messageUnreadCount,
                            messageUnreadLabel,
                            Modifier.align(Alignment.TopEnd).absoluteOffset(x = 5.dp, y = 0.dp),
                        )
                    }
                }
                NqrbCallIconButton(
                    label = callLabel,
                    enabled = callEnabled,
                    onClick = onCall,
                )
                Box {
                    IconButton(
                        modifier = Modifier.size(48.dp),
                        enabled = actionEnabled,
                        onClick = { actionsExpanded = true },
                    ) {
                        NqrbIcon(NqrbGlyph.More, overflowLabel ?: actionLabel, colors.textSecondary, Modifier.size(22.dp))
                    }
                    DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                        editNicknameLabel?.let { label ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    actionsExpanded = false
                                    onEditNickname()
                                },
                            )
                        }
                        blockLabel?.let { label ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { actionsExpanded = false; onBlock() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(actionLabel) },
                            enabled = actionEnabled,
                            onClick = {
                                actionsExpanded = false
                                onAction()
                            },
                        )
                    }
                }
            } else {
                TextButton(
                    modifier = Modifier.widthIn(min = 76.dp),
                    enabled = actionEnabled,
                    onClick = onAction,
                ) { Text(actionLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        }
    }
}

@Composable
private fun NqrbSwipeToCallBox(
    callLabel: String?,
    callEnabled: Boolean,
    onCall: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val colors = LocalNqrbColors.current
    val layoutDirection = LocalLayoutDirection.current
    var swipeOffsetPx by remember { mutableStateOf(0f) }
    val swipeThreshold = 80.dp
    val swipeModifier = if (callLabel != null && callEnabled) {
        Modifier
            .pointerInput(callEnabled) {
                val thresholdPx = swipeThreshold.toPx()
                val maxRevealPx = 112.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { swipeOffsetPx = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        swipeOffsetPx = (swipeOffsetPx + dragAmount).coerceIn(0f, maxRevealPx)
                    },
                    onDragEnd = {
                        val shouldCall = shouldStartCallFromSwipe(swipeOffsetPx, thresholdPx)
                        swipeOffsetPx = 0f
                        if (shouldCall) onCall()
                    },
                    onDragCancel = { swipeOffsetPx = 0f },
                )
            }
    } else {
        Modifier
    }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.accentSoft).then(swipeModifier)) {
        if (callLabel != null && callEnabled) {
            Row(
                Modifier.align(
                    if (layoutDirection == LayoutDirection.Rtl) Alignment.CenterEnd else Alignment.CenterStart,
                ).padding(horizontal = NqrbSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Xs),
            ) {
                NqrbIcon(NqrbGlyph.Call, callLabel, colors.accent, Modifier.size(22.dp))
                Text(callLabel, style = MaterialTheme.typography.labelMedium, color = colors.accent)
            }
        }
        content(Modifier.fillMaxWidth().absoluteOffset { IntOffset(swipeOffsetPx.roundToInt(), 0) })
    }
}

internal fun shouldStartCallFromSwipe(offsetPx: Float, thresholdPx: Float): Boolean = offsetPx >= thresholdPx

@Composable
internal fun NqrbCallIconButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalNqrbColors.current
    IconButton(
        modifier = Modifier.size(48.dp).clip(CircleShape).background(
            if (enabled) colors.accent else colors.accentSoft,
        ).semantics {
            contentDescription = label
            if (!enabled) disabled()
        },
        enabled = enabled,
        onClick = onClick,
    ) {
        NqrbIcon(
            NqrbGlyph.Call,
            null,
            if (enabled) MaterialTheme.colorScheme.onPrimary else colors.textSecondary,
            Modifier.size(20.dp),
        )
    }
}

internal fun shouldShowHistoryContactAdd(
    isGuestCall: Boolean,
    isSavedContact: Boolean?,
    canAddContact: Boolean,
    addedLocally: Boolean,
): Boolean = !isGuestCall && isSavedContact == false && canAddContact && !addedLocally

internal fun callableHistoryParticipant(
    item: CallHistoryItem,
): CallableParticipant? {
    val counterpartMembershipId = item.counterpartMembershipId ?: return null
    if (item.isGuestCall || !(item.canRedial || item.isSavedContact == true) || counterpartMembershipId.isBlank()) return null
    return CallableParticipant(
        counterpartMembershipId,
        item.participantDisplayName,
        CallingParticipantAvailability.Reachable,
    )
}

@Composable
private fun ProfileScreen(strings: NqrbStrings, appState: NqrbAppState, contactBook: NqrbContactBookSnapshot) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val colors = LocalNqrbColors.current
    val accountAction by appState.accountActionState.collectAsState()
    val accountProfile by appState.accountProfileState.collectAsState()
    val ringtone by appState.ringtone.selection.collectAsState()
    var deletionConfirmation by remember { mutableStateOf(NqrbDeletionConfirmation.Closed) }
    var showAccountControls by remember { mutableStateOf(false) }
    val operationInProgress = accountAction in setOf(
        NqrbAccountActionState.SigningOut,
        NqrbAccountActionState.Deleting,
    )
    LaunchedEffect(deletionConfirmation, operationInProgress) {
        appState.setReviewWorkflowActive(
            "profile-delete-confirmation",
            deletionConfirmation != NqrbDeletionConfirmation.Closed || operationInProgress,
        )
    }
    DisposableEffect(Unit) {
        onDispose { appState.setReviewWorkflowActive("profile-delete-confirmation", false) }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NqrbSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Md),
    ) {
        ProductHeader(strings, appState::openSettings)
        ProfileHero(strings, accountProfile, contactBook, appState::refreshAccountProfile)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm)) {
            ProfileShortcut(strings.savedContactsTitle, NqrbGlyph.People, Modifier.weight(1f)) {
                appState.selectTopLevel(NqrbDestination.People)
            }
            ProfileShortcut(strings.settings, NqrbGlyph.Settings, Modifier.weight(1f), appState::openSettings)
        }
        Surface(color = colors.surface, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(NqrbSpacing.Md), verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm)) {
                Text(strings.ringtoneTitle, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                Text(
                    when (ringtone) {
                        NqrbRingtone.Madar -> strings.ringtoneMadar
                        NqrbRingtone.Gentle -> strings.ringtoneGentle
                        NqrbRingtone.Classic -> strings.ringtoneClassic
                        NqrbRingtone.Clear -> strings.ringtoneClear
                        NqrbRingtone.Pulse -> strings.ringtonePulse
                        NqrbRingtone.Device -> strings.ringtoneDevice
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
                TextButton(onClick = appState::openSettings) { Text(strings.openSettings) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Xs)) {
            Text(strings.privacyAndSupport, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm)) {
                TextButton(onClick = { uriHandler.openUri(NqrbPublicSite.PrivacyPolicyUrl) }) { Text(strings.privacyPolicy) }
                TextButton(onClick = { uriHandler.openUri(NqrbPublicSite.SupportUrl) }) { Text(strings.support) }
            }
        }
        TextButton(onClick = { showAccountControls = !showAccountControls }) {
            Text(strings.accountInformation, color = colors.textSecondary)
        }
        if (showAccountControls) {
            Column(
                verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm),
            ) {
                Text(strings.deleteAccount, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                Text(strings.deleteAccountSummary, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                TextButton(
                    enabled = !operationInProgress,
                    onClick = { deletionConfirmation = NqrbDeletionConfirmation.Explanation },
                ) {
                    Text(strings.deleteAccount, color = colors.destructive)
                }
                TextButton(
                    enabled = !operationInProgress,
                    onClick = { uriHandler.openUri(NqrbPublicSite.AccountDeletionUrl) },
                ) {
                    Text(strings.accountDeletionHelp, color = colors.textSecondary)
                }
            }
        }
        when (accountAction) {
            NqrbAccountActionState.Deleting -> Text(
                strings.accountDeletionInProgress,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
            NqrbAccountActionState.DeletionFailed,
            NqrbAccountActionState.SignOutFailed,
            -> {
                Text(
                    if (accountAction == NqrbAccountActionState.DeletionFailed) {
                        strings.accountDeletionFailed
                    } else strings.signOutFailed,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.destructive,
                )
                TextButton(onClick = appState::dismissAccountActionError) {
                    Text(strings.cancel, color = colors.textSecondary)
                }
            }
            else -> Unit
        }
        if (showAccountControls) {
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !operationInProgress,
                onClick = { scope.launch { appState.logout() } },
            ) {
                Text(strings.logout, color = colors.textSecondary)
            }
        }
    }

    if (deletionConfirmation == NqrbDeletionConfirmation.Explanation) {
        AlertDialog(
            onDismissRequest = { deletionConfirmation = NqrbDeletionConfirmation.Closed },
            title = { Text(strings.deleteAccountFirstTitle) },
            text = { Text(strings.deleteAccountFirstBody) },
            confirmButton = {
                TextButton(onClick = {
                    deletionConfirmation = NqrbDeletionConfirmation.Final
                }) { Text(strings.continueToDelete, color = colors.destructive) }
            },
            dismissButton = {
                TextButton(onClick = { deletionConfirmation = NqrbDeletionConfirmation.Closed }) { Text(strings.cancel) }
            },
        )
    }
    if (deletionConfirmation == NqrbDeletionConfirmation.Final) {
        AlertDialog(
            onDismissRequest = {
                if (!operationInProgress) deletionConfirmation = NqrbDeletionConfirmation.Closed
            },
            title = { Text(strings.deleteAccountFinalTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Md)) {
                    Text(strings.deleteAccountFinalBody)
                    if (accountAction == NqrbAccountActionState.Deleting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(NqrbSpacing.Sm))
                            Text(strings.accountDeletionInProgress, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !operationInProgress,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.destructive),
                    onClick = {
                        if (deletionConfirmation == NqrbDeletionConfirmation.Final) {
                            scope.launch { appState.deleteAccount() }
                        }
                    },
                ) { Text(strings.confirmDeleteAccount) }
            },
            dismissButton = {
                TextButton(
                    enabled = !operationInProgress,
                    onClick = { deletionConfirmation = NqrbDeletionConfirmation.Closed },
                ) { Text(strings.cancel) }
            },
        )
    }
}

@Composable
private fun ProfileHero(
    strings: NqrbStrings,
    state: NqrbAccountProfileState,
    contactBook: NqrbContactBookSnapshot,
    onRetry: () -> Unit,
) {
    val colors = LocalNqrbColors.current
    val identity = accountIdentityPresentation(strings, state)
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(colors.elevatedSurface, colors.accentSoft)))
            .padding(NqrbSpacing.Lg),
    ) {
        Canvas(Modifier.align(Alignment.TopEnd).size(136.dp)) {
            drawCircle(colors.accent.copy(alpha = .3f), radius = size.minDimension * .47f, style = Stroke(1.dp.toPx()))
            drawCircle(colors.accent.copy(alpha = .23f), radius = size.minDimension * .32f, style = Stroke(1.dp.toPx()))
        }
        Column(verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Md)) {
            Box(Modifier.size(74.dp).clip(CircleShape).background(colors.callActionSurface), contentAlignment = Alignment.Center) {
                Text(identity?.displayName?.take(1)?.uppercase().orEmpty(), style = MaterialTheme.typography.headlineSmall, color = colors.callActionContent)
            }
            Text(identity?.displayName ?: strings.profileTitle, style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
            when (state) {
                NqrbAccountProfileState.Loading -> Text(strings.accountIdentityLoading, color = colors.textSecondary)
                NqrbAccountProfileState.Failed -> TextButton(onClick = onRetry) { Text(strings.retry) }
                is NqrbAccountProfileState.Available -> Text(
                    identity?.email.orEmpty().replace("@", "\u200B@"),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                NqrbAccountProfileState.Hidden -> Unit
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm)) {
                NqrbIcon(NqrbGlyph.Verified, strings.foundationStatus, colors.positive, Modifier.size(20.dp))
                Text(strings.foundationStatus, style = MaterialTheme.typography.labelMedium, color = colors.textPrimary)
            }
            if (contactBook.contactsState in setOf(NqrbContactBookLoadState.Ready, NqrbContactBookLoadState.Empty)) {
                Text("${strings.savedContactsTitle} · ${contactBook.contacts.size}", style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
            }
        }
    }
}

@Composable
private fun ProfileShortcut(label: String, glyph: NqrbGlyph, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalNqrbColors.current
    Surface(modifier.clickable(onClick = onClick), color = colors.surface, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(NqrbSpacing.Md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm)) {
            NqrbIcon(glyph, label, colors.accent, Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
        }
    }
}

internal data class NqrbAccountIdentityPresentation(
    val nameLabel: String,
    val displayName: String,
    val emailLabel: String,
    val email: String,
)

internal fun accountIdentityPresentation(
    strings: NqrbStrings,
    state: NqrbAccountProfileState,
): NqrbAccountIdentityPresentation? {
    val profile = (state as? NqrbAccountProfileState.Available)?.profile ?: return null
    return NqrbAccountIdentityPresentation(
        nameLabel = strings.accountName,
        displayName = profile.displayName.trim().ifEmpty { strings.unavailableAccountName },
        emailLabel = strings.emailAddress,
        email = profile.email.trim().ifEmpty { strings.unavailableAccountEmail },
    )
}

@Composable
private fun AccountIdentityCard(
    strings: NqrbStrings,
    state: NqrbAccountProfileState,
    onRetry: () -> Unit,
) {
    if (state == NqrbAccountProfileState.Hidden) return
    val colors = LocalNqrbColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.surface,
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
    ) {
        Column(
            Modifier.padding(NqrbSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Md),
        ) {
            Text(strings.accountInformation, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            when (state) {
                NqrbAccountProfileState.Loading -> Text(
                    strings.accountIdentityLoading,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
                NqrbAccountProfileState.Failed -> {
                    Text(
                        strings.accountIdentityError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                    TextButton(onClick = onRetry) { Text(strings.retry, color = colors.accent) }
                }
                is NqrbAccountProfileState.Available -> {
                    val presentation = accountIdentityPresentation(strings, state) ?: return@Column
                    AccountIdentityRow(presentation.nameLabel, presentation.displayName)
                    AccountIdentityRow(presentation.emailLabel, presentation.email)
                }
                NqrbAccountProfileState.Hidden -> Unit
            }
        }
    }
}

@Composable
private fun AccountIdentityRow(label: String, value: String) {
    val colors = LocalNqrbColors.current
    Column(verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Xs)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
    }
}

@Composable
private fun HomeScreen(
    strings: NqrbStrings,
    chatStrings: NqrbChatStrings,
    appState: NqrbAppState,
    directory: CallingDirectorySnapshot,
    contactBook: NqrbContactBookSnapshot,
    chat: ChatSnapshot,
    microphoneBlocked: Boolean,
) {
    val colors = LocalNqrbColors.current
    val privateDirectory = privateCallingDirectory(directory, contactBook)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NqrbSpacing.Lg, vertical = NqrbSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Md),
    ) {
        ProductHeader(strings, appState::openSettings)
        Text(
            strings.madarGreeting,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        if (privateDirectory.status == CallingDirectoryStatus.Ready && privateDirectory.participants.isNotEmpty()) {
            MadarOrbit(strings, chatStrings, privateDirectory, contactBook, chat, appState)
        } else {
            MadarEmptyCircle(strings, appState)
        }
        Surface(
            modifier = Modifier.fillMaxWidth().clickable(onClick = appState::openChats),
            color = colors.elevatedSurface,
            shape = RoundedCornerShape(16.dp),
        ) {
            Row(
                Modifier.padding(NqrbSpacing.Md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Md),
            ) {
                Box(Modifier.size(50.dp).clip(RoundedCornerShape(13.dp)).background(colors.accentSoft), contentAlignment = Alignment.Center) {
                    NqrbIcon(NqrbGlyph.Chat, chatStrings.chats, colors.accent, Modifier.size(25.dp))
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm)) {
                        Text(chatStrings.chats, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                        NqrbUnreadBadge(chat.totalUnreadCount, chatStrings.unreadCount(chat.totalUnreadCount))
                    }
                    Text(chatStrings.empty, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                }
            }
        }
        if (privateDirectory.status == CallingDirectoryStatus.Ready) Surface(
            modifier = Modifier.fillMaxWidth().clickable { appState.selectTopLevel(NqrbDestination.People) },
            color = colors.elevatedSurface,
            shape = RoundedCornerShape(16.dp),
        ) {
            Row(
                Modifier.padding(NqrbSpacing.Md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Md),
            ) {
                Box(Modifier.size(50.dp).clip(RoundedCornerShape(13.dp)).background(colors.accentSoft), contentAlignment = Alignment.Center) {
                    NqrbIcon(NqrbGlyph.People, strings.madarInviteTitle, colors.accent, Modifier.size(25.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(strings.madarInviteTitle, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                    Text(strings.madarInviteBody, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                }
            }
        }
        CallingDirectorySection(strings, chatStrings, privateDirectory, contactBook, chat, appState)
        if (microphoneBlocked) InfoNote(strings.microphoneDenied)
        Spacer(Modifier.height(NqrbSpacing.Sm))
    }
}

internal fun privateCallingDirectory(directory: CallingDirectorySnapshot, contactBook: NqrbContactBookSnapshot): CallingDirectorySnapshot {
    val savedIds = contactBook.contacts.map { it.membershipId }.toSet()
    val blockedIds = contactBook.blockedAccounts.map { it.membershipId }.toSet()
    return when (contactBook.contactsState) {
        NqrbContactBookLoadState.Ready -> directory.copy(
            status = if (directory.status == CallingDirectoryStatus.Ready && directory.participants.none { it.membershipId in savedIds && it.membershipId !in blockedIds }) CallingDirectoryStatus.Empty else directory.status,
            participants = directory.participants.filter { it.membershipId in savedIds && it.membershipId !in blockedIds },
        )
        NqrbContactBookLoadState.Empty -> CallingDirectorySnapshot(
            status = CallingDirectoryStatus.Empty,
            isRefreshing = directory.isRefreshing || contactBook.contactsRefreshing,
            refreshFailed = directory.refreshFailed,
        )
        NqrbContactBookLoadState.Error -> CallingDirectorySnapshot(
            status = CallingDirectoryStatus.Error,
            refreshFailed = directory.refreshFailed,
        )
        else -> CallingDirectorySnapshot(
            status = CallingDirectoryStatus.Loading,
            isRefreshing = directory.isRefreshing || contactBook.contactsRefreshing,
        )
    }
}

internal fun privateContactDisplayName(contactBook: NqrbContactBookSnapshot, membershipId: String, publicName: String): String =
    (contactBook.contacts.firstOrNull { it.membershipId == membershipId }
        ?: contactBook.resolvedCallContacts[membershipId])?.nickname?.takeIf(String::isNotBlank) ?: publicName


@Composable
private fun MadarEmptyCircle(strings: NqrbStrings, appState: NqrbAppState) {
    val colors = LocalNqrbColors.current
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(colors.elevatedSurface, colors.accentSoft)))
            .clickable { appState.selectTopLevel(NqrbDestination.People) }
            .padding(NqrbSpacing.Lg),
    ) {
        Canvas(Modifier.align(Alignment.CenterEnd).size(130.dp)) {
            drawCircle(colors.accent.copy(alpha = .32f), radius = size.minDimension * .46f, style = Stroke(1.dp.toPx()))
            drawCircle(colors.accent.copy(alpha = .18f), radius = size.minDimension * .32f, style = Stroke(1.dp.toPx()))
        }
        Column(Modifier.fillMaxWidth(.68f), verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm)) {
            NqrbIcon(NqrbGlyph.People, strings.madarInviteTitle, colors.accent, Modifier.size(32.dp))
            Text(strings.madarInviteTitle, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            Text(strings.madarInviteBody, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        }
    }
}

@Composable
private fun MadarOrbit(strings: NqrbStrings, chatStrings: NqrbChatStrings, directory: CallingDirectorySnapshot, contactBook: NqrbContactBookSnapshot, chat: ChatSnapshot, appState: NqrbAppState) {
    val colors = LocalNqrbColors.current
    val people = if (directory.status == CallingDirectoryStatus.Ready) directory.participants.take(3) else emptyList()
    Box(Modifier.fillMaxWidth().height(245.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(217.dp)) {
            drawCircle(colors.border, radius = size.minDimension * .48f, style = Stroke(1.dp.toPx()))
            drawCircle(colors.border.copy(alpha = .8f), radius = size.minDimension * .34f, style = Stroke(1.dp.toPx()))
        }
        Box(
            Modifier.size(74.dp).clip(CircleShape).background(colors.callActionSurface)
                .clickable { appState.selectTopLevel(NqrbDestination.People) }
                .semantics { role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            NqrbIcon(NqrbGlyph.Call, strings.madarCircleHint, colors.callActionContent, Modifier.size(31.dp))
        }
        people.getOrNull(0)?.let { person -> OrbitPerson(
            person, strings, chatStrings, contactBook, Modifier.align(Alignment.TopStart).padding(start = 7.dp, top = 67.dp),
            unreadCount = chat.unreadCountForCounterpart(person.membershipId),
            onCall = { appState.requestOutgoingCall(person) },
            onMessage = { appState.openChatWith(person.membershipId) },
        ) }
        people.getOrNull(1)?.let { person -> OrbitPerson(
            person, strings, chatStrings, contactBook, Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 22.dp),
            unreadCount = chat.unreadCountForCounterpart(person.membershipId),
            onCall = { appState.requestOutgoingCall(person) },
            onMessage = { appState.openChatWith(person.membershipId) },
        ) }
        people.getOrNull(2)?.let { person -> OrbitPerson(
            person, strings, chatStrings, contactBook, Modifier.align(Alignment.BottomEnd).padding(end = 25.dp, bottom = 31.dp),
            unreadCount = chat.unreadCountForCounterpart(person.membershipId),
            onCall = { appState.requestOutgoingCall(person) },
            onMessage = { appState.openChatWith(person.membershipId) },
        ) }
        Text(
            strings.madarCircleHint,
            Modifier.align(Alignment.BottomCenter),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun OrbitPerson(
    participant: CallableParticipant,
    strings: NqrbStrings,
    chatStrings: NqrbChatStrings,
    contactBook: NqrbContactBookSnapshot,
    modifier: Modifier = Modifier,
    unreadCount: Int,
    onCall: () -> Unit,
    onMessage: () -> Unit,
) {
    val colors = LocalNqrbColors.current
    val available = true
    val displayName = privateContactDisplayName(contactBook, participant.membershipId, participant.displayName)
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(colors.accentSoft).clickable(enabled = available, onClick = onCall).semantics {
                role = Role.Button
                contentDescription = "${strings.call}: $displayName"
                if (!available) disabled()
            },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                displayName.trim().take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = colors.accent,
            )
        }
        Box(
            Modifier.heightIn(min = 48.dp).widthIn(min = 72.dp).clickable(onClick = onMessage).semantics {
                role = Role.Button
                contentDescription = chatStrings.openChatWith(displayName)
            },
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                displayName.substringBefore(' ').take(10),
                style = MaterialTheme.typography.labelMedium,
                color = if (available) colors.textPrimary else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            NqrbUnreadBadge(
                unreadCount,
                chatStrings.unreadCount(unreadCount),
                Modifier.align(Alignment.TopEnd).absoluteOffset(x = 14.dp, y = (-9).dp),
            )
        }
    }
}

@Composable
private fun CallingDirectorySection(
    strings: NqrbStrings,
    chatStrings: NqrbChatStrings,
    directory: CallingDirectorySnapshot,
    contactBook: NqrbContactBookSnapshot,
    chat: ChatSnapshot,
    appState: NqrbAppState,
) {
    val colors = LocalNqrbColors.current
    Text(
        strings.callablePeopleTitle,
        style = MaterialTheme.typography.titleLarge,
        color = colors.textPrimary,
    )
    BackgroundRefreshIndicator(directory.isRefreshing || contactBook.contactsRefreshing)
    if (directory.refreshFailed) InfoNote(strings.callingDirectoryError)
    when (directory.status) {
        CallingDirectoryStatus.Idle,
        CallingDirectoryStatus.Loading,
        -> NqrbListSkeleton(strings.callingDirectoryLoading)

        CallingDirectoryStatus.Empty -> {
            InfoNote(strings.callingDirectoryEmpty)
            DirectoryRefreshAction(strings.retry, appState::refreshCallingDirectory)
        }

        CallingDirectoryStatus.Error -> {
            InfoNote(strings.callingDirectoryError)
            DirectoryRefreshAction(strings.retry, appState::refreshCallingDirectory)
        }

        CallingDirectoryStatus.Ready -> {
            InfoNote(strings.swipeToCallHint)
            directory.participants.forEach { participant ->
                CallableParticipantCard(
                    participant = participant,
                    displayName = privateContactDisplayName(contactBook, participant.membershipId, participant.displayName),
                    unreadCount = chat.unreadCountForCounterpart(participant.membershipId),
                    unreadLabel = chatStrings.unreadCount(chat.unreadCountForCounterpart(participant.membershipId)),
                    status = nqrbDirectoryAvailability(strings, participant),
                    callLabel = strings.call,
                    canCall = true,
                    onCall = { appState.requestOutgoingCall(participant) },
                    nameActionLabel = chatStrings.openChatWith(
                        privateContactDisplayName(contactBook, participant.membershipId, participant.displayName),
                    ),
                    onNameClick = { appState.openChatWith(participant.membershipId) },
                )
            }
            DirectoryRefreshAction(strings.refreshCallingDirectory, appState::refreshCallingDirectory)
        }
    }
}

@Composable
private fun DirectoryRefreshAction(label: String, onClick: () -> Unit) {
    TextButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Text(label)
    }
}

@Composable
private fun CallableParticipantCard(
    participant: CallableParticipant,
    displayName: String,
    unreadCount: Int = 0,
    unreadLabel: String = "",
    status: String,
    callLabel: String,
    canCall: Boolean,
    onCall: () -> Unit,
    nameActionLabel: String,
    onNameClick: () -> Unit,
) {
    val colors = LocalNqrbColors.current
    NqrbSwipeToCallBox(callLabel, canCall, onCall) { swipeModifier ->
        Surface(
            modifier = swipeModifier,
            color = colors.surface,
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
        ) {
            Row(
                modifier = Modifier.padding(NqrbSpacing.Md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Md),
            ) {
                ParticipantAvatar(displayName)
                Column(
                    Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClick = onNameClick).semantics {
                        role = Role.Button
                        contentDescription = nameActionLabel
                    },
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm)) {
                        Text(
                            displayName,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        NqrbUnreadBadge(unreadCount, unreadLabel)
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (participant.availability == CallingParticipantAvailability.Online) {
                            colors.positive
                        } else {
                            colors.textSecondary
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                NqrbCallIconButton(
                    label = callLabel,
                    enabled = canCall,
                    onClick = onCall,
                )
            }
        }
    }
}

@Composable
private fun MicrophoneExplanationScreen(strings: NqrbStrings, appState: NqrbAppState) {
    BrandedFlowFrame(strings, appState::openSettings) {
        FlowHero(NqrbGlyph.Microphone, strings.microphoneTitle, strings.microphoneBody)
        Button(
            modifier = Modifier.fillMaxWidth().height(54.dp),
            onClick = appState::continueAfterMicrophoneExplanation,
        ) { Text(strings.continueCall) }
        TextButton(onClick = appState::cancelMicrophoneExplanation, modifier = Modifier.fillMaxWidth()) {
            Text(strings.cancel)
        }
    }
}

@Composable
private fun InCallScreen(
    strings: NqrbStrings,
    call: CallSessionSnapshot,
    contactBook: NqrbContactBookSnapshot,
    appState: NqrbAppState,
    onMinimizeCall: () -> Unit,
) {
    val colors = LocalNqrbColors.current
    LaunchedEffect(call.callId, call.direction, call.state) {
        val callId = call.callId
        if (callId != null && call.direction == CallDirection.Incoming &&
            call.state in setOf(CallState.Ringing, CallState.Answering, CallState.Connecting)) {
            appState.confirmIncomingCallPresented(callId)
        }
    }
    val displayName = call.participant?.let { privateContactDisplayName(contactBook, it.membershipId, it.displayName) }.orEmpty()
    val presentation = nqrbCallPresentation(strings, call)
    Box(Modifier.fillMaxSize()) {
        if (canMinimizeCall(call.state)) {
            TextButton(
                onClick = onMinimizeCall,
                modifier = Modifier.align(Alignment.TopStart).padding(NqrbSpacing.Md)
                    .clip(RoundedCornerShape(14.dp)).background(colors.accentSoft),
            ) {
                NqrbIcon(NqrbGlyph.MinimizeCall, strings.browseDuringCall, colors.accent, Modifier.size(32.dp).rotate(225f))
                Spacer(Modifier.width(NqrbSpacing.Xs))
                Text(strings.browseDuringCall)
            }
        }
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(NqrbSpacing.Lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(88.dp).clip(CircleShape).background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                NqrbIcon(NqrbGlyph.Profile, displayName, colors.accent, Modifier.size(46.dp))
            }
            Spacer(Modifier.height(NqrbSpacing.Lg))
            Text(
                displayName,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.headlineSmall,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                presentation.status,
                modifier = Modifier.fillMaxWidth().semantics {
                    liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
                },
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            if (call.state == CallState.Active) {
                val minutes = call.elapsedSeconds / 60
                val seconds = call.elapsedSeconds % 60
                Text("$minutes:${seconds.toString().padStart(2, '0')}", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            }
            Spacer(Modifier.height(NqrbSpacing.Xl))
            if (presentation.terminal) {
                Button(
                    onClick = appState::dismissCallStatus,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(strings.back) }
            } else if (call.direction == CallDirection.Incoming && call.state == CallState.Ringing) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    CallControl(NqrbGlyph.Call, strings.answerCall, selected = true) {
                        appState.requestAcceptIncomingCall()
                    }
                    CallControl(NqrbGlyph.Call, strings.declineCall, selected = true, destructive = true) {
                        appState.rejectIncomingCall()
                    }
                }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                CallControl(
                    glyph = if (call.media.muted) NqrbGlyph.MicrophoneOff else NqrbGlyph.Microphone,
                    label = if (call.media.muted) strings.unmute else strings.mute,
                    selected = call.media.muted,
                ) { appState.setCallMuted(!call.media.muted) }
                val routeTarget = call.media.speakerControlTarget()
                CallControl(
                    glyph = NqrbGlyph.Speaker,
                    label = when (routeTarget) {
                        CallAudioRoute.Speaker -> strings.speaker
                        CallAudioRoute.Earpiece -> strings.earpiece
                        null -> if (call.media.route == CallAudioRoute.Speaker) strings.speaker else strings.audioRoute
                        else -> strings.audioRoute
                    },
                    selected = call.media.route == CallAudioRoute.Speaker,
                    enabled = routeTarget != null,
                ) {
                    routeTarget?.let(appState::requestCallRoute)
                }
                CallControl(NqrbGlyph.Call, strings.endCall, selected = true, destructive = true) {
                    appState.endCall(CallTerminationReason.Local)
                }
            }
        }
    }
}

@Composable
private fun CompactCallBar(
    strings: NqrbStrings,
    call: CallSessionSnapshot,
    contactBook: NqrbContactBookSnapshot,
    onRestoreCall: () -> Unit,
    onToggleMute: () -> Unit,
) {
    val colors = LocalNqrbColors.current
    val displayName = call.participant?.let {
        privateContactDisplayName(contactBook, it.membershipId, it.displayName)
    }.orEmpty().ifBlank { strings.activeCall }
    val status = nqrbCallPresentation(strings, call).status
    val duration = if (call.state == CallState.Active) {
        "${call.elapsedSeconds / 60}:${(call.elapsedSeconds % 60).toString().padStart(2, '0')}"
    } else null
    Surface(
        color = colors.compactCallSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NqrbSpacing.Md, vertical = NqrbSpacing.Xs)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClickLabel = strings.returnToCall, role = Role.Button, onClick = onRestoreCall),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = NqrbSpacing.Md, vertical = NqrbSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm),
        ) {
            NqrbIcon(NqrbGlyph.Call, strings.activeCall, colors.accent, Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(displayName, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$status · ${strings.returnToCall}", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (duration != null) {
                Text(duration, style = MaterialTheme.typography.labelLarge, color = colors.textPrimary)
            }
            IconButton(
                onClick = onToggleMute,
                enabled = call.state != CallState.Ending,
                modifier = Modifier.size(48.dp).clip(CircleShape).background(colors.surface),
            ) {
                NqrbIcon(
                    if (call.media.muted) NqrbGlyph.MicrophoneOff else NqrbGlyph.Microphone,
                    if (call.media.muted) strings.unmute else strings.mute,
                    if (call.media.muted) colors.destructive else colors.accent,
                    Modifier.size(21.dp),
                )
            }
        }
    }
}

@Composable
private fun CallControl(
    glyph: NqrbGlyph,
    label: String,
    selected: Boolean,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = LocalNqrbColors.current
    val color = when {
        destructive -> colors.destructive
        selected -> colors.accent
        else -> colors.textPrimary
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            modifier = Modifier.size(58.dp).clip(CircleShape).background(if (selected) colors.accentSoft else colors.surface),
            enabled = enabled,
            onClick = onClick,
        ) { NqrbIcon(glyph, label, color, Modifier.size(28.dp)) }
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
    }
}

@Composable
private fun ProductHeader(strings: NqrbStrings, onSettings: () -> Unit) {
    val colors = LocalNqrbColors.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark(Modifier.size(44.dp), colors.accent)
        Spacer(Modifier.width(NqrbSpacing.Sm))
        Column(Modifier.weight(1f)) {
            Text(strings.productName, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            Text(strings.productNameArabic, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        }
        IconButton(onClick = onSettings) {
            NqrbIcon(NqrbGlyph.Settings, strings.openSettings, colors.textPrimary, Modifier.size(25.dp))
        }
    }
}

@Composable
internal fun BrandMark(modifier: Modifier, tint: Color) {
    Canvas(modifier) {
        val strokeWidth = 2.2.dp.toPx()
        drawCircle(tint, size.minDimension * .12f, Offset(size.width * .25f, size.height * .5f))
        drawCircle(tint, size.minDimension * .12f, Offset(size.width * .75f, size.height * .5f))
        drawArc(
            tint,
            startAngle = 210f,
            sweepAngle = 120f,
            useCenter = false,
            topLeft = Offset(size.width * .35f, size.height * .29f),
            size = androidx.compose.ui.geometry.Size(size.width * .3f, size.height * .42f),
            style = Stroke(strokeWidth, cap = StrokeCap.Round),
        )
        drawLine(tint, Offset(size.width * .4f, size.height * .5f), Offset(size.width * .6f, size.height * .5f), strokeWidth, StrokeCap.Round)
    }
}

@Composable
private fun HeroCard(strings: NqrbStrings) {
    val colors = LocalNqrbColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.surface.copy(alpha = .93f),
        shape = RoundedCornerShape(30.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
        shadowElevation = 10.dp,
    ) {
        Column(
            Modifier.padding(horizontal = NqrbSpacing.Lg, vertical = NqrbSpacing.Xl),
            verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Md),
        ) {
            Text(
                strings.heroEyebrow,
                style = MaterialTheme.typography.labelMedium,
                color = colors.accent,
                fontWeight = FontWeight.Bold,
            )
            Text(strings.heroTitle, style = MaterialTheme.typography.displaySmall, color = colors.textPrimary)
            Text(strings.heroBody, style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
        }
    }
}

@Composable
private fun FoundationStatus(strings: NqrbStrings) {
    val colors = LocalNqrbColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.accentSoft)
            .padding(NqrbSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NqrbIcon(NqrbGlyph.Verified, strings.foundationStatus, colors.positive, Modifier.size(26.dp))
        Spacer(Modifier.width(NqrbSpacing.Md))
        Column {
            Text(strings.foundationStatus, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            Text(strings.foundationStatusBody, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        }
    }
}

@Composable
private fun ConceptCard(
    glyph: NqrbGlyph,
    title: String,
    body: String,
    iconDescription: String,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalNqrbColors.current
    Surface(
        modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick),
        color = colors.elevatedSurface,
        shape = RoundedCornerShape(22.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(NqrbSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                NqrbIcon(glyph, iconDescription, colors.accent, Modifier.size(26.dp))
            }
            Spacer(Modifier.width(NqrbSpacing.Md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                Spacer(Modifier.height(NqrbSpacing.Xs))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String, body: String, strings: NqrbStrings, onSettings: () -> Unit) {
    val colors = LocalNqrbColors.current
    Column(
        Modifier.fillMaxSize().padding(NqrbSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Lg),
    ) {
        ProductHeader(strings, onSettings)
        Surface(
            Modifier.fillMaxWidth(),
            color = colors.surface,
            shape = RoundedCornerShape(28.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
        ) {
            Column(Modifier.padding(NqrbSpacing.Xl), verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
                Text(body, style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    strings: NqrbStrings,
    languageTag: String,
    appState: NqrbAppState,
    layoutDirection: LayoutDirection,
    activity: CallActivitySnapshot,
    onPreviewRingtone: (NqrbRingtone) -> Unit,
    onChoosePhoneRingtone: () -> Unit,
    notificationsEnabled: Boolean,
    onOpenNotificationSettings: () -> Unit,
    callTime: (String, String) -> NqrbCallTime,
) {
    val colors = LocalNqrbColors.current
    val uriHandler = LocalUriHandler.current
    val locale by appState.locale.state.collectAsState()
    val appearance by appState.appearance.state.collectAsState()
    val ringtone by appState.ringtone.selection.collectAsState()
    val phoneRingtoneName by appState.ringtone.deviceToneName.collectAsState()
    var confirmingUsageReset by remember { mutableStateOf(false) }
    LaunchedEffect(confirmingUsageReset) {
        appState.setReviewWorkflowActive("settings-usage-reset", confirmingUsageReset)
    }
    DisposableEffect(Unit) {
        onDispose { appState.setReviewWorkflowActive("settings-usage-reset", false) }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NqrbSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { appState.navigation.navigateBack() }) {
                NqrbIcon(
                    NqrbGlyph.Back,
                    strings.back,
                    colors.textPrimary,
                    Modifier.size(24.dp).scale(if (layoutDirection == LayoutDirection.Rtl) -1f else 1f, 1f),
                )
            }
            Spacer(Modifier.width(NqrbSpacing.Sm))
            Text(strings.settings, style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
        }
        SettingsGroup(strings.language, NqrbGlyph.Language, strings.language) {
            ChoiceRow(
                options = listOf("ar" to strings.arabic, "en" to strings.english),
                selected = locale.languageTag,
                selectedDescription = strings.selected,
                onSelect = appState.locale::selectLanguage,
            )
        }
        SettingsGroup(strings.appearance, NqrbGlyph.Appearance, strings.appearance) {
            ChoiceRow(
                options = listOf(
                    AppearancePreference.System to strings.system,
                    AppearancePreference.Light to strings.light,
                    AppearancePreference.Dark to strings.dark,
                ),
                selected = appearance.preference,
                selectedDescription = strings.selected,
                onSelect = appState.appearance::select,
            )
        }
        SettingsGroup(strings.ringtoneTitle, NqrbGlyph.Ringtone, strings.ringtoneTitle) {
            Text(strings.ringtoneBody, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            listOf(
                NqrbRingtone.Madar to strings.ringtoneMadar,
                NqrbRingtone.Gentle to strings.ringtoneGentle,
                NqrbRingtone.Classic to strings.ringtoneClassic,
                NqrbRingtone.Clear to strings.ringtoneClear,
                NqrbRingtone.Pulse to strings.ringtonePulse,
            ).forEach { (choice, label) ->
                RingtoneChoice(
                    label = label,
                    selected = ringtone == choice,
                    previewLabel = strings.previewRingtone,
                    previewDescription = "${strings.previewRingtone}: $label",
                    onSelect = { appState.ringtone.select(choice) },
                    onPreview = { onPreviewRingtone(choice) },
                )
            }
            RingtoneChoice(
                label = if (ringtone == NqrbRingtone.Device) phoneRingtoneName ?: strings.ringtoneDevice
                    else strings.choosePhoneRingtone,
                selected = ringtone == NqrbRingtone.Device,
                previewLabel = strings.previewRingtone,
                previewDescription = "${strings.previewRingtone}: ${phoneRingtoneName ?: strings.ringtoneDevice}",
                onSelect = onChoosePhoneRingtone,
                onPreview = { onPreviewRingtone(NqrbRingtone.Device) },
            )
        }
        if (!notificationsEnabled) SettingsGroup(strings.notificationsTitle, NqrbGlyph.Call, strings.notificationsTitle) {
            Text(strings.notificationsDisabledBody, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            Button(modifier = Modifier.fillMaxWidth().height(48.dp), onClick = onOpenNotificationSettings) {
                Text(strings.openNotificationSettings)
            }
        }
        SettingsGroup(strings.privacyAndSupport, NqrbGlyph.Link, strings.privacyAndSupport) {
            nqrbPublicLinks(strings).forEach { link ->
                TextButton(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    onClick = { uriHandler.openUri(link.url) },
                ) {
                    Text(link.label)
                }
            }
        }
        SettingsGroup(strings.dataUsage, NqrbGlyph.History, strings.dataUsage) {
            BackgroundRefreshIndicator(activity.usageRefreshing)
            if (activity.usageRefreshFailed) InfoNote(strings.historyError)
            when (activity.usageState) {
                CallActivityLoadState.Loading, CallActivityLoadState.Idle -> NqrbListSkeleton(strings.historyLoading, rows = 1)
                CallActivityLoadState.Error -> InfoNote(strings.historyError)
                else -> activity.usage?.let { usage ->
                    Text("${strings.from}: ${callTime(usage.startedAtUtc, languageTag).fullLabel}", color = colors.textSecondary)
                    UsageLine(strings.sent, usage.bytesSent)
                    UsageLine(strings.received, usage.bytesReceived)
                    UsageLine(strings.total, usage.totalBytes)
                    if (confirmingUsageReset) {
                        InfoNote(strings.resetUsageConfirmation)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { confirmingUsageReset = false }) { Text(strings.cancel) }
                            TextButton(onClick = {
                                confirmingUsageReset = false
                                appState.resetUsage()
                            }) { Text(strings.confirmResetUsage) }
                        }
                    } else {
                        TextButton(onClick = { confirmingUsageReset = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(strings.resetUsage)
                        }
                    }
                }
            }
        }
        Text(
            "${strings.appVersion} ${appState.appVersion}",
            Modifier.fillMaxWidth().padding(bottom = NqrbSpacing.Md),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RingtoneChoice(
    label: String,
    selected: Boolean,
    previewLabel: String,
    previewDescription: String,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
) {
    val colors = LocalNqrbColors.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) colors.accentSoft else colors.elevatedSurface)
            .clickable(onClick = onSelect)
            .padding(horizontal = NqrbSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
        TextButton(onClick = onPreview, modifier = Modifier.semantics { contentDescription = previewDescription }) {
            Text(previewLabel)
        }
    }
}

@Composable
private fun CallHistoryScreen(
    strings: NqrbStrings,
    languageTag: String,
    appState: NqrbAppState,
    activity: CallActivitySnapshot,
    contactBook: NqrbContactBookSnapshot,
    directory: CallingDirectorySnapshot,
    callTime: (String, String) -> NqrbCallTime,
) {
    val colors = LocalNqrbColors.current
    val addedFromHistory by appState.addedHistoryContactCalls.collectAsState()
    activity.selected?.let { detail -> CallDetailScreen(strings, languageTag, appState, contactBook, detail, callTime); return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NqrbSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Md)) {
        ProductHeader(strings, appState::openSettings)
        Text(strings.historyTitle, style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
        Text(strings.historyBody, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        HistoryFilterRow(strings, activity.historyFilter, appState::selectCallHistoryFilter)
        BackgroundRefreshIndicator(activity.historyRefreshing)
        if (activity.historyRefreshFailed) InfoNote(strings.historyError)
        if (activity.history.any { callableHistoryParticipant(it) != null }) InfoNote(strings.swipeToCallHint)
        if (contactBook.mutationState == NqrbContactMutationState.Error) InfoNote(strings.contactAddUnavailable)
        when (activity.historyState) {
            CallActivityLoadState.Idle, CallActivityLoadState.Loading -> NqrbListSkeleton(strings.historyLoading)
            CallActivityLoadState.Empty -> InfoNote(strings.historyEmpty)
            CallActivityLoadState.Error -> { InfoNote(strings.historyError); DirectoryRefreshAction(strings.retry, appState::refreshCallHistory) }
            CallActivityLoadState.Ready -> {
                var previousDay: String? = null
                activity.history.forEach { item ->
                    val callable = callableHistoryParticipant(item)?.takeUnless {
                        contactBook.blockedAccounts.any { blocked -> blocked.membershipId == it.membershipId }
                    }
                    val addedLocally = item.callId in addedFromHistory ||
                        item.counterpartMembershipId?.let { it in addedFromHistory } == true
                    val whenLocal = callTime(item.startedAtUtc, languageTag)
                    if (previousDay != whenLocal.dayKey) {
                        Text(
                            whenLocal.dayLabel,
                            Modifier.padding(top = NqrbSpacing.Sm),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textSecondary,
                        )
                        previousDay = whenLocal.dayKey
                    }
                    NqrbSwipeToCallBox(strings.call, callable != null, { callable?.let(appState::requestOutgoingCall) }) { swipeModifier ->
                        Surface(
                            modifier = swipeModifier.clickable { appState.openCallDetail(item.callId) },
                            color = colors.surface,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                        Row(
                            Modifier.padding(horizontal = NqrbSpacing.Sm, vertical = NqrbSpacing.Xs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm),
                        ) {
                            ParticipantAvatar(item.participantDisplayName)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Xs)) {
                                Text(
                                    item.participantDisplayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = colors.textPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (item.isGuestCall) Text(
                                    strings.guestHistory,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.accent,
                                )
                                val outcome = outcomeLabel(strings, item.outcome)
                                Text(
                                    "${directionLabel(strings, item.direction)} · $outcome",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (item.outcome.equals("missed", true) || item.outcome.equals("failed", true)) colors.destructive else colors.textSecondary,
                                )
                                val connectedSeconds = item.connectedDurationSeconds
                                if (connectedSeconds != null && connectedSeconds > 0) {
                                    Text("${strings.connectedDuration}: ${formatDuration(connectedSeconds)}", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                                }
                            }
                            Column(
                                Modifier.widthIn(min = 112.dp, max = 148.dp),
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Xs),
                            ) {
                                Text(whenLocal.timeLabel, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                                if (callable != null) IconButton(
                                    modifier = Modifier.size(48.dp),
                                    onClick = { appState.requestOutgoingCall(callable) },
                                ) {
                                    NqrbIcon(NqrbGlyph.Call, strings.call, colors.accent, Modifier.size(22.dp))
                                }
                                if (shouldShowHistoryContactAdd(item.isGuestCall, item.isSavedContact,
                                        item.canAddContact, addedLocally)) TextButton(
                                    enabled = contactBook.mutationState != NqrbContactMutationState.Saving,
                                    onClick = { appState.addNqrbContactFromCallHistory(item.callId, item.counterpartMembershipId) },
                                ) {
                                    Text(
                                        strings.addFromHistory,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.End,
                                    )
                                }
                            }
                        }
                        }
                    }
                }
            }
        }
        if (activity.historyState == CallActivityLoadState.Ready && activity.historyHasMore)
            DirectoryRefreshAction(strings.loadMore, appState::loadMoreCallHistory)
    }
}

@Composable
private fun NotificationsScreen(
    strings: NqrbStrings,
    languageTag: String,
    appState: NqrbAppState,
    notifications: List<SemanticNotification>,
) {
    val colors = LocalNqrbColors.current
    LaunchedEffect(Unit) { appState.markAllNotificationsRead() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NqrbSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Md),
    ) {
        ProductHeader(strings, appState::openSettings)
        Text(strings.notificationInboxTitle, style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
        Text(strings.notificationInboxBody, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        if (notifications.isEmpty()) {
            InfoNote(strings.notificationInboxEmpty)
        } else {
            notifications.forEach { notification ->
                val conversationId = notification.chatConversationId()
                Surface(
                    modifier = Modifier.fillMaxWidth().then(
                        if (conversationId != null) Modifier.clickable { appState.openChat(conversationId) } else Modifier,
                    ),
                    color = if (notification.isRead) colors.surface else colors.accentSoft,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = NqrbSpacing.Md, vertical = NqrbSpacing.Sm),
                        horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm),
                        verticalAlignment = Alignment.Top,
                    ) {
                        NqrbIcon(NqrbGlyph.Notifications, strings.notifications, colors.accent, Modifier.size(24.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Xs)) {
                            Text(
                                notificationTitle(notification, languageTag),
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val body = notificationBody(notification, languageTag)
                            if (body.isNotBlank()) {
                                Text(
                                    body,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textSecondary,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun notificationTitle(notification: SemanticNotification, languageTag: String): String =
    if (languageTag.startsWith("ar")) {
        notification.titleAr.ifBlank { notification.titleEn }
    } else {
        notification.titleEn.ifBlank { notification.titleAr }
    }.ifBlank { "Nqrb" }

private fun notificationBody(notification: SemanticNotification, languageTag: String): String =
    if (languageTag.startsWith("ar")) {
        notification.bodyAr.ifBlank { notification.bodyEn }
    } else {
        notification.bodyEn.ifBlank { notification.bodyAr }
    }

private fun SemanticNotification.chatConversationId(): String? =
    ((destination as? SemanticNotificationDestination.Internal)?.route)
        ?.takeIf { it.startsWith(ChatNotificationDestinationPrefix) }
        ?.removePrefix(ChatNotificationDestinationPrefix)
        ?.takeIf { ChatConversationIdPattern.matches(it) }

private const val ChatNotificationDestinationPrefix = "chat:"
private val ChatConversationIdPattern =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

@Composable
private fun HistoryFilterRow(
    strings: NqrbStrings,
    selected: CallHistoryFilter,
    onSelect: (CallHistoryFilter) -> Unit,
) {
    val filters = listOf(
        CallHistoryFilter.All to strings.allCalls,
        CallHistoryFilter.Missed to strings.missed,
        CallHistoryFilter.Incoming to strings.incoming,
        CallHistoryFilter.Outgoing to strings.outgoing,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Xs)) {
        filters.forEach { (filter, label) ->
            val isSelected = filter == selected
            Surface(
                modifier = Modifier.weight(1f).semantics {
                    this.selected = isSelected
                    role = Role.Button
                },
                color = if (isSelected) LocalNqrbColors.current.accentSoft else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, LocalNqrbColors.current.accent) else null,
            ) {
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelect(filter) },
                    contentPadding = PaddingValues(horizontal = NqrbSpacing.Xs),
                ) {
                    Text(
                        label,
                        color = if (isSelected) LocalNqrbColors.current.accent else LocalNqrbColors.current.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CallDetailScreen(strings: NqrbStrings, languageTag: String, appState: NqrbAppState, contactBook: NqrbContactBookSnapshot, detail: CallHistoryDetail, callTime: (String, String) -> NqrbCallTime) {
    val colors = LocalNqrbColors.current
    val addedFromHistory by appState.addedHistoryContactCalls.collectAsState()
    var confirmBlock by remember { mutableStateOf(false) }
    val counterpartId = detail.counterpartMembershipId
    val blockReviewKey = counterpartId?.let { "history-block:$it" }
    LaunchedEffect(confirmBlock, blockReviewKey) {
        blockReviewKey?.let { appState.setReviewWorkflowActive(it, confirmBlock) }
    }
    DisposableEffect(blockReviewKey) {
        onDispose {
            blockReviewKey?.let { appState.setReviewWorkflowActive(it, false) }
        }
    }
    val isBlocked = counterpartId != null && contactBook.blockedAccounts.any { it.membershipId == counterpartId }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NqrbSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Md)) {
        TextButton(onClick = appState::closeCallDetail) { Text(strings.back) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Md)) {
            ParticipantAvatar(detail.participantDisplayNames.firstOrNull().orEmpty())
            Text(detail.participantDisplayNames.joinToString(), style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
        }
        Text("${directionLabel(strings, detail.direction)} · ${outcomeLabel(strings, detail.outcome)}", color = colors.textSecondary)
        if (detail.isGuestCall) Text(strings.guestHistory, color = colors.accent,
            style = MaterialTheme.typography.labelMedium)
        val addedLocally = detail.callId in addedFromHistory ||
            counterpartId?.let { it in addedFromHistory } == true
        if (shouldShowHistoryContactAdd(detail.isGuestCall, detail.isSavedContact,
                detail.canAddContact, addedLocally)) Button(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            enabled = contactBook.mutationState != NqrbContactMutationState.Saving,
            onClick = { appState.addNqrbContactFromCallHistory(detail.callId, counterpartId) },
        ) { Text(strings.addFromHistory) }
        if (!detail.isGuestCall && counterpartId != null) {
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = contactBook.blockState != NqrbBlockState.Working,
                onClick = {
                    if (isBlocked) appState.unblockNqrbAccount(counterpartId)
                    else confirmBlock = true
                },
            ) { Text(if (isBlocked) strings.unblockContact else strings.blockContact) }
        }
        if (contactBook.blockState == NqrbBlockState.Error) {
            InfoNote(if (contactBook.blockErrorIsLoad) strings.blockedLoadError else strings.blockError)
            DirectoryRefreshAction(strings.retry, appState::refreshBlockedAccounts)
        }
        Text("${strings.callTime}: ${callTime(detail.startedAtUtc, languageTag).fullLabel}", color = colors.textSecondary)
        detail.ringingDurationSeconds?.let { Text("${strings.ringingDuration}: ${formatDuration(it)}", color = colors.textSecondary) }
        detail.connectedDurationSeconds?.let { Text("${strings.connectedDuration}: ${formatDuration(it)}", color = colors.textSecondary) }
        UsageLine(strings.sent, detail.bytesSent ?: 0)
        UsageLine(strings.received, detail.bytesReceived ?: 0)
        UsageLine(strings.total, detail.totalBytes ?: 0)
        val connectedSeconds = detail.connectedDurationSeconds
        val totalBytes = detail.totalBytes
        if (connectedSeconds != null && connectedSeconds > 0 && totalBytes != null)
            Text("${humanBytes(totalBytes * 60 / connectedSeconds)} ${strings.averagePerMinute}", color = colors.textSecondary)
    }
    if (confirmBlock && counterpartId != null) AlertDialog(
        onDismissRequest = { confirmBlock = false },
        title = { Text(strings.blockConfirmTitle) },
        text = { Text(strings.blockConfirmBody) },
        confirmButton = { TextButton(onClick = {
            confirmBlock = false
            appState.blockNqrbAccount(counterpartId)
        }) { Text(strings.blockContact) } },
        dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text(strings.cancel) } },
    )
}

@Composable
private fun ParticipantAvatar(displayName: String) {
    val colors = LocalNqrbColors.current
    Box(
        Modifier.size(46.dp).clip(CircleShape).background(colors.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        Text(displayName.trim().take(1).uppercase(), color = colors.accent, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun UsageLine(label: String, bytes: Long) {
    val colors = LocalNqrbColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = colors.textSecondary); Text(humanBytes(bytes), color = colors.textPrimary)
    }
}

private fun humanBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "${decimalUnits(bytes, 1024L * 1024, 2)} MiB"
    bytes >= 1024 -> "${decimalUnits(bytes, 1024, 1)} KiB"
    else -> "$bytes B"
}
private fun decimalUnits(value: Long, unit: Long, decimals: Int): String {
    val scale = if (decimals == 2) 100 else 10
    val scaled = value * scale / unit
    return "${scaled / scale}.${(scaled % scale).toString().padStart(decimals, '0')}"
}
private fun formatDuration(seconds: Long) = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
private fun directionLabel(strings: NqrbStrings, value: String) = if (value.equals("incoming", true)) strings.incoming else strings.outgoing
private fun outcomeLabel(strings: NqrbStrings, value: String?) = when (value?.lowercase()) {
    "completed" -> strings.completed; "rejected" -> strings.rejected; "missed" -> strings.missed
    "cancelled" -> strings.cancelled; "expired" -> strings.expired; "failed" -> strings.failed
    else -> strings.ringing
}

@Composable
private fun SettingsGroup(title: String, glyph: NqrbGlyph, iconDescription: String, content: @Composable () -> Unit) {
    val colors = LocalNqrbColors.current
    Surface(
        Modifier.fillMaxWidth(),
        color = colors.surface,
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
    ) {
        Column(Modifier.padding(NqrbSpacing.Md), verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NqrbIcon(glyph, iconDescription, colors.accent, Modifier.size(24.dp))
                Spacer(Modifier.width(NqrbSpacing.Sm))
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            }
            content()
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    selectedDescription: String,
    onSelect: (T) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm)) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Choice(
                label = label,
                isSelected = isSelected,
                selectedDescription = selectedDescription,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(value) },
            )
        }
    }
}

@Composable
private fun Choice(
    label: String,
    isSelected: Boolean,
    selectedDescription: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalNqrbColors.current
    Surface(
        modifier = modifier
            .semantics {
                role = Role.RadioButton
                selected = isSelected
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        color = if (isSelected) colors.accentSoft else colors.elevatedSurface,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) colors.accent else colors.border),
    ) {
        Text(
            text = if (isSelected) "$label · $selectedDescription" else label,
            modifier = Modifier.padding(horizontal = NqrbSpacing.Sm, vertical = 12.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            color = if (isSelected) colors.accent else colors.textSecondary,
        )
    }
}

@Composable
private fun NqrbBottomBar(
    current: NqrbDestination,
    strings: NqrbStrings,
    notifications: List<SemanticNotification>,
    missedCallBadgeCount: Int,
    onSelect: (NqrbDestination) -> Unit,
) {
    val colors = LocalNqrbColors.current
    val items = listOf(
        BottomNavItem(NqrbDestination.Home, NqrbGlyph.Home, strings.home),
        BottomNavItem(NqrbDestination.History, NqrbGlyph.History, strings.history, missedCallBadgeCount),
        BottomNavItem(NqrbDestination.Notifications, NqrbGlyph.Notifications, strings.notifications, notifications.count { !it.isRead }),
        BottomNavItem(NqrbDestination.People, NqrbGlyph.People, strings.people),
        BottomNavItem(NqrbDestination.Profile, NqrbGlyph.Profile, strings.profile),
    )
    Surface(color = colors.surface, shadowElevation = 16.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                BottomItem(item, current == item.destination, colors, onSelect)
            }
        }
    }
}

private data class BottomNavItem(
    val destination: NqrbDestination,
    val glyph: NqrbGlyph,
    val label: String,
    val badgeCount: Int = 0,
)

@Composable
private fun BottomItem(
    item: BottomNavItem,
    isSelected: Boolean,
    colors: NqrbColors,
    onSelect: (NqrbDestination) -> Unit,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { onSelect(item.destination) }
            .semantics {
                role = Role.Tab
                selected = isSelected
            }
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box {
            NqrbIcon(item.glyph, item.label, if (isSelected) colors.accent else colors.textSecondary, Modifier.size(23.dp))
            if (item.badgeCount > 0) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).absoluteOffset(x = 9.dp, y = (-6).dp),
                    color = colors.destructive,
                    shape = CircleShape,
                ) {
                    Text(
                        if (item.badgeCount > 9) "9+" else item.badgeCount.toString(),
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Text(
            item.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isSelected) colors.accent else colors.textSecondary,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
internal expect fun NqrbSystemBackHandler(enabled: Boolean, onBack: () -> Unit)
