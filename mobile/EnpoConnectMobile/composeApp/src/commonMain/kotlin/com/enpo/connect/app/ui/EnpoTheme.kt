package com.enpo.connect.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.botglobal.mobile.platform.appearance.ResolvedAppearance

private val EnpoGreen = Color(0xFF009657)
private val EnpoGreenDark = Color(0xFF007A48)
private val EnpoGreenLight = Color(0xFF40C989)

private val LightColors = lightColorScheme(
    primary = EnpoGreen,
    onPrimary = Color.White,
    secondary = EnpoGreenDark,
    background = Color(0xFFF7F9F8),
    onBackground = Color(0xFF17201C),
    surface = Color.White,
    onSurface = Color(0xFF17201C),
)

private val DarkColors = darkColorScheme(
    primary = EnpoGreenLight,
    onPrimary = Color(0xFF002113),
    secondary = EnpoGreenLight,
    background = Color(0xFF071A13),
    onBackground = Color(0xFFE6F1EB),
    surface = Color(0xFF10271E),
    onSurface = Color(0xFFE6F1EB),
)

@Composable
fun EnpoTheme(
    appearance: ResolvedAppearance,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (appearance == ResolvedAppearance.Dark) DarkColors else LightColors,
        content = content,
    )
}
