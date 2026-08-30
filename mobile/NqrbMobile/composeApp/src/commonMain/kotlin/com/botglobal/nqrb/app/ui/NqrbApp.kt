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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.botglobal.mobile.platform.localization.ContentDirection
import com.botglobal.nqrb.app.state.NqrbAppState
import com.botglobal.nqrb.app.state.NqrbDestination

@Composable
fun NqrbApp(
    appState: NqrbAppState = remember { NqrbAppState() },
    onResolvedAppearanceChanged: (ResolvedAppearance) -> Unit = {},
) {
    val locale by appState.locale.state.collectAsState()
    val appearance by appState.appearance.state.collectAsState()
    val backStack by appState.navigation.backStack.collectAsState()
    val systemIsDark = isSystemInDarkTheme()
    val strings = nqrbStrings(locale.languageTag)
    val layoutDirection = if (locale.direction == ContentDirection.RightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr

    LaunchedEffect(systemIsDark) {
        appState.appearance.updateSystemAppearance(systemIsDark)
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
) {
    val colors = LocalNqrbColors.current
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            if (destination != NqrbDestination.Settings) {
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
            AnimatedContent(destination) { current ->
                when (current) {
                    NqrbDestination.Home -> HomeScreen(strings, appState::openSettings)
                    NqrbDestination.Settings -> SettingsScreen(
                        strings = strings,
                        appState = appState,
                        layoutDirection = layoutDirection,
                    )
                    NqrbDestination.History -> PlaceholderScreen(strings.historyTitle, strings.historyBody, strings, appState::openSettings)
                    NqrbDestination.People -> PlaceholderScreen(strings.peopleTitle, strings.peopleBody, strings, appState::openSettings)
                    NqrbDestination.Profile -> PlaceholderScreen(strings.profileTitle, strings.profileBody, strings, appState::openSettings)
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(strings: NqrbStrings, onSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NqrbSpacing.Lg, vertical = NqrbSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Md),
    ) {
        ProductHeader(strings, onSettings)
        HeroCard(strings)
        FoundationStatus(strings)
        ConceptCard(
            glyph = NqrbGlyph.Call,
            title = strings.primaryCallTitle,
            body = strings.primaryCallBody,
            iconDescription = strings.call,
        )
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
