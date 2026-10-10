package com.botglobal.nqrb.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private const val WelcomeRevealDurationMillis = 220

@Composable
internal fun NqrbWelcomeUi(
    strings: NqrbStrings,
    loading: Boolean,
    onSettings: (() -> Unit)? = null,
    title: String? = null,
    body: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = LocalNqrbColors.current
    val reveal = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        reveal.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = WelcomeRevealDurationMillis,
                easing = FastOutSlowInEasing,
            ),
        )
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        val adaptiveSpace = (maxHeight * .08f).coerceIn(NqrbSpacing.Md, 56.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NqrbSpacing.Lg, vertical = NqrbSpacing.Md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                if (onSettings != null) {
                    IconButton(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        onClick = onSettings,
                    ) {
                        NqrbIcon(
                            glyph = NqrbGlyph.Settings,
                            contentDescription = strings.openSettings,
                            tint = colors.textPrimary,
                            modifier = Modifier.size(25.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(adaptiveSpace))
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .graphicsLayer { alpha = .72f + (.28f * reveal.value) }
                    .scale(.97f + (.03f * reveal.value)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BrandMark(
                    modifier = Modifier.size(112.dp),
                    tint = colors.accent,
                )
                Spacer(Modifier.height(NqrbSpacing.Md))
                Text(
                    text = strings.welcomeProductName,
                    style = MaterialTheme.typography.displaySmall,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                if (title != null) {
                    Spacer(Modifier.height(NqrbSpacing.Xl))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center,
                    )
                }
                if (body != null) {
                    Spacer(Modifier.height(NqrbSpacing.Sm))
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(Modifier.height(NqrbSpacing.Xl))
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(32.dp)
                        .semantics { contentDescription = strings.welcomeLoading },
                    color = colors.accent,
                    strokeWidth = 3.dp,
                )
            } else {
                Column(
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    content = content,
                )
            }
            Spacer(Modifier.height(adaptiveSpace))
        }
    }
}
