package com.botglobal.nqrb.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.botglobal.mobile.platform.appearance.AppearancePreference
import com.botglobal.mobile.platform.appearance.ResolvedAppearance
import com.botglobal.mobile.platform.calling.CallAudioRoute
import com.botglobal.mobile.platform.calling.CallDirection
import com.botglobal.mobile.platform.calling.CallSessionSnapshot
import com.botglobal.mobile.platform.calling.CallState
import com.botglobal.mobile.platform.calling.CallTerminationReason
import com.botglobal.mobile.platform.contacts.ContactsSnapshot
import com.botglobal.mobile.platform.contacts.ContactsStatus
import com.botglobal.mobile.platform.contacts.DeviceContact
import com.botglobal.mobile.platform.identity.FederatedAuthenticationError
import com.botglobal.mobile.platform.identity.FederatedAuthenticationState
import com.botglobal.mobile.platform.localization.ContentDirection
import com.botglobal.nqrb.app.state.NqrbAppState
import com.botglobal.nqrb.app.state.NqrbDestination
import kotlinx.coroutines.launch

@Composable
fun NqrbApp(
    appState: NqrbAppState = remember { NqrbAppState() },
    onResolvedAppearanceChanged: (ResolvedAppearance) -> Unit = {},
) {
    val locale by appState.locale.state.collectAsState()
    val appearance by appState.appearance.state.collectAsState()
    val backStack by appState.navigation.backStack.collectAsState()
    val authentication by appState.identity.state.collectAsState()
    val contacts by appState.contacts.state.collectAsState()
    val call by appState.calling.state.collectAsState()
    val microphoneExplanation by appState.microphoneExplanationVisible.collectAsState()
    val microphoneBlocked by appState.microphonePermissionBlocked.collectAsState()
    val systemIsDark = isSystemInDarkTheme()
    val strings = nqrbStrings(locale.languageTag)
    val layoutDirection = if (locale.direction == ContentDirection.RightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr

    LaunchedEffect(systemIsDark) {
        appState.appearance.updateSystemAppearance(systemIsDark)
    }
    LaunchedEffect(appState) {
        appState.startup()
    }
    SideEffect {
        onResolvedAppearanceChanged(appearance.resolved)
    }

    NqrbTheme(appearance.resolved) {
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
            NqrbSystemBackHandler(
                enabled = backStack.size > 1,
                onBack = { appState.navigation.navigateBack() },
            )
            NqrbShell(
                destination = backStack.last(),
                strings = strings,
                appState = appState,
                layoutDirection = layoutDirection,
                authentication = authentication,
                contacts = contacts,
                call = call,
                microphoneExplanation = microphoneExplanation,
                microphoneBlocked = microphoneBlocked,
            )
        }
    }
}

@Composable
private fun NqrbShell(
    destination: NqrbDestination,
    strings: NqrbStrings,
    appState: NqrbAppState,
    layoutDirection: LayoutDirection,
    authentication: FederatedAuthenticationState,
    contacts: ContactsSnapshot,
    call: CallSessionSnapshot,
    microphoneExplanation: Boolean,
    microphoneBlocked: Boolean,
) {
    val colors = LocalNqrbColors.current
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            if (destination in NQRB_TOP_LEVEL_DESTINATIONS && call.state !in VisibleCallStates && !microphoneExplanation) {
                NqrbBottomBar(destination, strings, appState::selectTopLevel)
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
                .padding(contentPadding),
        ) {
            when {
                microphoneExplanation -> MicrophoneExplanationScreen(strings, appState)
                call.state in VisibleCallStates -> InCallScreen(strings, call, appState)
                else -> AnimatedContent(destination) { current -> when (current) {
                    NqrbDestination.SignIn -> SignInScreen(strings, authentication, appState)
                    NqrbDestination.ContactsOnboarding -> ContactsOnboardingScreen(strings, appState)
                    NqrbDestination.Home -> HomeScreen(strings, appState, microphoneBlocked)
                    NqrbDestination.Settings -> SettingsScreen(
                        strings = strings,
                        appState = appState,
                        layoutDirection = layoutDirection,
                    )
                    NqrbDestination.History -> PlaceholderScreen(strings.historyTitle, strings.historyBody, strings, appState::openSettings)
                    NqrbDestination.People -> PeopleScreen(strings, contacts, appState)
                    NqrbDestination.Profile -> ProfileScreen(strings, appState)
                } }
            }
        }
    }
}

private val NQRB_TOP_LEVEL_DESTINATIONS = NqrbAppState.TOP_LEVEL_DESTINATIONS
private val VisibleCallStates = setOf(
    CallState.Preparing, CallState.Connecting, CallState.Ringing, CallState.Answering, CallState.Active,
    CallState.Reconnecting, CallState.Ending,
)

@Composable
private fun SignInScreen(
    strings: NqrbStrings,
    authentication: FederatedAuthenticationState,
    appState: NqrbAppState,
) {
    val scope = rememberCoroutineScope()
    val busy = authentication is FederatedAuthenticationState.SigningIn ||
        authentication is FederatedAuthenticationState.RestoringSession
    BrandedFlowFrame(strings, appState::openSettings) {
        FlowHero(NqrbGlyph.Profile, strings.signInTitle, strings.signInBody)
        Button(
            modifier = Modifier.fillMaxWidth().height(54.dp),
            enabled = !busy,
            onClick = { scope.launch { appState.signInWithGoogle() } },
        ) {
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
private fun ContactsOnboardingScreen(strings: NqrbStrings, appState: NqrbAppState) {
    val scope = rememberCoroutineScope()
    BrandedFlowFrame(strings, appState::openSettings) {
        FlowHero(NqrbGlyph.People, strings.contactsOnboardingTitle, strings.contactsOnboardingBody)
        InfoNote(strings.contactsStayLocal)
        Button(
            modifier = Modifier.fillMaxWidth().height(54.dp),
            onClick = { scope.launch { appState.allowContacts() } },
        ) {
            Text(strings.allowContacts)
        }
        TextButton(onClick = appState::skipContacts, modifier = Modifier.fillMaxWidth()) {
            Text(strings.notNow)
        }
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
private fun PeopleScreen(strings: NqrbStrings, snapshot: ContactsSnapshot, appState: NqrbAppState) {
    val scope = rememberCoroutineScope()
    val colors = LocalNqrbColors.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NqrbSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Md),
    ) {
        ProductHeader(strings, appState::openSettings)
        Text(strings.peopleTitle, style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
        when (snapshot.status) {
            ContactsStatus.Available -> snapshot.contacts.forEach { ContactCard(it) }
            ContactsStatus.Empty -> InfoNote(strings.contactsEmpty)
            ContactsStatus.Denied -> ContactsPermissionState(strings.contactsDenied, strings.allowContacts) {
                scope.launch { appState.requestContactsFromPeople() }
            }
            ContactsStatus.PermanentlyDenied -> InfoNote(strings.contactsPermanentlyDenied)
            ContactsStatus.Unavailable -> InfoNote(strings.contactsDenied)
            ContactsStatus.Loading -> InfoNote(strings.refreshContacts)
            ContactsStatus.NotRequested -> ContactsPermissionState(strings.peopleBody, strings.allowContacts) {
                scope.launch { appState.requestContactsFromPeople() }
            }
        }
    }
}

@Composable
private fun ContactsPermissionState(body: String, action: String, onAction: () -> Unit) {
    InfoNote(body)
    Button(onClick = onAction, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(action) }
}

@Composable
private fun ContactCard(contact: DeviceContact) {
    val colors = LocalNqrbColors.current
    Surface(
        Modifier.fillMaxWidth(),
        color = colors.surface,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
    ) {
        Row(Modifier.padding(NqrbSpacing.Md), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).clip(CircleShape).background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Text(contact.displayName.trim().take(1).uppercase(), color = colors.accent, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(NqrbSpacing.Md))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Xs)) {
                Text(contact.displayName, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                contact.phoneNumbers.forEach { number ->
                    Text(
                        "\u2066${number.displayValue}\u2069",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileScreen(strings: NqrbStrings, appState: NqrbAppState) {
    val scope = rememberCoroutineScope()
    val colors = LocalNqrbColors.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NqrbSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Lg),
    ) {
        ProductHeader(strings, appState::openSettings)
        FlowHero(NqrbGlyph.Profile, strings.profileTitle, strings.profileBody)
        TextButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { scope.launch { appState.logout() } },
        ) {
            Text(strings.logout, color = colors.destructive)
        }
    }
}

@Composable
private fun HomeScreen(strings: NqrbStrings, appState: NqrbAppState, microphoneBlocked: Boolean) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NqrbSpacing.Lg, vertical = NqrbSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Md),
    ) {
        ProductHeader(strings, appState::openSettings)
        HeroCard(strings)
        FoundationStatus(strings)
        ConceptCard(
            glyph = NqrbGlyph.Call,
            title = strings.primaryCallTitle,
            body = strings.primaryCallBody,
            iconDescription = strings.call,
        )
        if (appState.hasConfiguredCallTarget()) {
            Button(
                modifier = Modifier.fillMaxWidth().height(54.dp),
                onClick = appState::requestOutgoingCall,
            ) {
                NqrbIcon(NqrbGlyph.Call, strings.startCall, Color.White, Modifier.size(22.dp))
                Spacer(Modifier.width(NqrbSpacing.Sm))
                Text(strings.startCall)
            }
        }
        if (microphoneBlocked) InfoNote(strings.microphoneDenied)
        ConceptCard(
            glyph = NqrbGlyph.Link,
            title = strings.createCallLinkTitle,
            body = strings.createCallLinkBody,
            iconDescription = strings.createCallLinkTitle,
        )
        Spacer(Modifier.height(NqrbSpacing.Sm))
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
private fun InCallScreen(strings: NqrbStrings, call: CallSessionSnapshot, appState: NqrbAppState) {
    val colors = LocalNqrbColors.current
    val status = when (call.state) {
        CallState.Preparing, CallState.Connecting, CallState.Answering -> strings.connecting
        CallState.Ringing -> strings.ringing
        CallState.Active -> strings.activeCall
        CallState.Reconnecting -> strings.reconnecting
        CallState.Ending -> strings.endingCall
        CallState.Failed -> strings.callFailed
        else -> strings.activeCall
    }
    Column(
        Modifier.fillMaxSize().padding(NqrbSpacing.Lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            NqrbIcon(NqrbGlyph.Profile, call.participant?.displayName.orEmpty(), colors.accent, Modifier.size(46.dp))
        }
        Spacer(Modifier.height(NqrbSpacing.Lg))
        Text(call.participant?.displayName.orEmpty(), style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
        Text(status, style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
        if (call.state == CallState.Active) {
            val minutes = call.elapsedSeconds / 60
            val seconds = call.elapsedSeconds % 60
            Text("$minutes:${seconds.toString().padStart(2, '0')}", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
        }
        Spacer(Modifier.height(NqrbSpacing.Xl))
        if (call.direction == CallDirection.Incoming && call.state == CallState.Ringing) {
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
                glyph = NqrbGlyph.Microphone,
                label = if (call.media.muted) strings.unmute else strings.mute,
                selected = call.media.muted,
            ) { appState.setCallMuted(!call.media.muted) }
            CallControl(
                glyph = NqrbGlyph.Speaker,
                label = if (call.media.route == CallAudioRoute.Speaker) strings.earpiece else strings.speaker,
                selected = call.media.route == CallAudioRoute.Speaker,
            ) {
                appState.requestCallRoute(
                    if (call.media.route == CallAudioRoute.Speaker) CallAudioRoute.Earpiece else CallAudioRoute.Speaker,
                )
            }
            CallControl(NqrbGlyph.Call, strings.endCall, selected = true, destructive = true) {
                appState.endCall(CallTerminationReason.Local)
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
private fun BrandMark(modifier: Modifier, tint: Color) {
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
private fun ConceptCard(glyph: NqrbGlyph, title: String, body: String, iconDescription: String) {
    val colors = LocalNqrbColors.current
    Surface(
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
    appState: NqrbAppState,
    layoutDirection: LayoutDirection,
) {
    val colors = LocalNqrbColors.current
    val locale by appState.locale.state.collectAsState()
    val appearance by appState.appearance.state.collectAsState()
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
    }
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
    onSelect: (NqrbDestination) -> Unit,
) {
    val colors = LocalNqrbColors.current
    val items = listOf(
        Triple(NqrbDestination.Home, NqrbGlyph.Home, strings.home),
        Triple(NqrbDestination.History, NqrbGlyph.History, strings.history),
        Triple(NqrbDestination.People, NqrbGlyph.People, strings.people),
        Triple(NqrbDestination.Profile, NqrbGlyph.Profile, strings.profile),
    )
    Surface(color = colors.surface, shadowElevation = 16.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.take(2).forEach { (destination, glyph, label) ->
                BottomItem(destination, glyph, label, current == destination, colors, onSelect)
            }
            Box(
                Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(colors.callActionSurface)
                    .semantics {
                        role = Role.Button
                        disabled()
                    },
                contentAlignment = Alignment.Center,
            ) {
                NqrbIcon(NqrbGlyph.Call, strings.call, colors.background, Modifier.size(30.dp))
            }
            items.drop(2).forEach { (destination, glyph, label) ->
                BottomItem(destination, glyph, label, current == destination, colors, onSelect)
            }
        }
    }
}

@Composable
private fun BottomItem(
    destination: NqrbDestination,
    glyph: NqrbGlyph,
    label: String,
    isSelected: Boolean,
    colors: NqrbColors,
    onSelect: (NqrbDestination) -> Unit,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { onSelect(destination) }
            .semantics {
                role = Role.Tab
                selected = isSelected
            }
            .padding(horizontal = 9.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        NqrbIcon(glyph, label, if (isSelected) colors.accent else colors.textSecondary, Modifier.size(23.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isSelected) colors.accent else colors.textSecondary,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
internal expect fun NqrbSystemBackHandler(enabled: Boolean, onBack: () -> Unit)
