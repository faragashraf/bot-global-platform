package com.enpo.connect.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.enpo.connect.app.state.EnpoStartupAnimationSpec
import com.enpo.connect.app.state.EnpoStartupBranding
import com.enpo.connect.resources.Res
import com.enpo.connect.resources.connect_logo_dark
import com.enpo.connect.resources.connect_logo_light
import com.enpo.connect.resources.organization_logo_dark
import com.enpo.connect.resources.organization_logo_light
import com.enpo.connect.resources.splash_cinematic_background
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

enum class EnpoPairedTab {
    Settings,
    Notifications,
    Profile,
}

@Composable
fun EnpoSplash(strings: EnpoStrings) {
    val density = LocalDensity.current
    val travel = with(density) { EnpoStartupAnimationSpec.LogoTravelDp.dp.toPx() }
    val organizationX = androidx.compose.runtime.remember {
        Animatable(travel * EnpoStartupAnimationSpec.EgyptPostInitialTranslationMultiplier)
    }
    val connectX = androidx.compose.runtime.remember {
        Animatable(travel * EnpoStartupAnimationSpec.ConnectInitialTranslationMultiplier)
    }
    val brandAlpha = androidx.compose.runtime.remember { Animatable(0f) }
    val dividerAlpha = androidx.compose.runtime.remember { Animatable(0f) }
    val loadingAlpha = androidx.compose.runtime.remember { Animatable(0f) }
    val sceneScale = androidx.compose.runtime.remember { Animatable(1.045f) }

    LaunchedEffect(Unit) {
        coroutineScope {
            launch {
                organizationX.animateTo(
                    0f,
                    tween(
                        durationMillis = EnpoStartupAnimationSpec.LogoTravelDurationMillis,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
            launch {
                connectX.animateTo(
                    0f,
                    tween(
                        durationMillis = EnpoStartupAnimationSpec.LogoTravelDurationMillis,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
            launch {
                brandAlpha.animateTo(
                    1f,
                    tween(durationMillis = EnpoStartupAnimationSpec.BrandFadeDurationMillis),
                )
            }
            launch {
                sceneScale.animateTo(
                    1f,
                    tween(
                        durationMillis = EnpoStartupAnimationSpec.BackgroundScaleDurationMillis,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
        }

        delay(EnpoStartupAnimationSpec.SecondaryRevealDelayMillis)
        coroutineScope {
            launch {
                dividerAlpha.animateTo(
                    1f,
                    tween(durationMillis = EnpoStartupAnimationSpec.DividerFadeDurationMillis),
                )
            }
            launch {
                loadingAlpha.animateTo(
                    1f,
                    tween(durationMillis = EnpoStartupAnimationSpec.LoadingFadeDurationMillis),
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050D11)),
    ) {
        Image(
            painter = painterResource(Res.drawable.splash_cinematic_background),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = sceneScale.value
                    scaleY = sceneScale.value
                },
            contentScale = ContentScale.Crop,
        )
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .graphicsLayer { translationY = -235f },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(Res.drawable.connect_logo_dark),
                    contentDescription = EnpoStartupBranding.Connect,
                    modifier = Modifier
                        .weight(1f)
                        .height(112.dp)
                        .graphicsLayer {
                            translationX = connectX.value
                            alpha = brandAlpha.value
                        },
                    contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier
                        .width(1.dp)
                        .height(124.dp)
                        .alpha(dividerAlpha.value)
                        .background(Color(0xFF4FE0A4).copy(alpha = .78f)),
                )
                Spacer(Modifier.width(12.dp))
                Image(
                    painter = painterResource(Res.drawable.organization_logo_dark),
                    contentDescription = EnpoStartupBranding.EgyptPost,
                    modifier = Modifier
                        .weight(1f)
                        .height(148.dp)
                        .graphicsLayer {
                            translationX = organizationX.value
                            alpha = brandAlpha.value
                        },
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 68.dp)
                .alpha(loadingAlpha.value),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(42.dp),
                color = Color(0xFF40D89A),
                trackColor = Color.White.copy(alpha = .14f),
                strokeWidth = 3.dp,
            )
            Spacer(Modifier.height(16.dp))
            Text(strings.loading, color = Color.White.copy(alpha = .88f), fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun EnpoBrandHeader(isDark: Boolean) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier.fillMaxWidth().height(72.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(
                    if (isDark) Res.drawable.connect_logo_dark else Res.drawable.connect_logo_light,
                ),
                contentDescription = "Connect",
                modifier = Modifier.weight(1f).height(50.dp),
                contentScale = ContentScale.Fit,
            )
            Box(
                Modifier
                    .padding(horizontal = 12.dp)
                    .width(1.dp)
                    .height(48.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = .55f)),
            )
            Image(
                painter = painterResource(
                    if (isDark) Res.drawable.organization_logo_dark else Res.drawable.organization_logo_light,
                ),
                contentDescription = "Egypt Post",
                modifier = Modifier.weight(1f).height(62.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
fun EnpoPairedScreen(
    strings: EnpoStrings,
    selectedTab: EnpoPairedTab?,
    unreadNotificationCount: Int,
    onSettings: () -> Unit,
    onNotifications: () -> Unit,
    onProfile: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            content = content,
        )
        EnpoBottomBar(
            strings = strings,
            selectedTab = selectedTab,
            unreadNotificationCount = unreadNotificationCount,
            onSettings = onSettings,
            onNotifications = onNotifications,
            onProfile = onProfile,
        )
    }
}

@Composable
private fun EnpoBottomBar(
    strings: EnpoStrings,
    selectedTab: EnpoPairedTab?,
    unreadNotificationCount: Int,
    onSettings: () -> Unit,
    onNotifications: () -> Unit,
    onProfile: () -> Unit,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        NavigationBar {
            NavigationBarItem(
                selected = selectedTab == EnpoPairedTab.Settings,
                onClick = onSettings,
                icon = { Icon(Icons.Default.Settings, contentDescription = strings.settings) },
                label = { Text(strings.settings, maxLines = 1) },
            )
            NavigationBarItem(
                selected = selectedTab == EnpoPairedTab.Notifications,
                onClick = onNotifications,
                icon = {
                    BadgedBox(
                        badge = {
                            if (unreadNotificationCount > 0) {
                                Badge { Text(unreadNotificationCount.coerceAtMost(99).toString()) }
                            }
                        },
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = strings.notifications)
                    }
                },
                label = { Text(strings.notifications, maxLines = 1) },
            )
            NavigationBarItem(
                selected = selectedTab == EnpoPairedTab.Profile,
                onClick = onProfile,
                icon = { Icon(Icons.Default.Person, contentDescription = strings.profile) },
                label = { Text(strings.profile, maxLines = 1) },
            )
        }
    }
}
