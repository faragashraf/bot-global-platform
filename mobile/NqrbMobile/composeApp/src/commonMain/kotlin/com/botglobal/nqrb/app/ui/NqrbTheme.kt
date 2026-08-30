package com.botglobal.nqrb.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import com.botglobal.mobile.platform.appearance.ResolvedAppearance

@Immutable
data class NqrbColors(
    val background: Color,
    val backgroundGlow: Color,
    val surface: Color,
    val elevatedSurface: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val border: Color,
    val accent: Color,
    val accentSoft: Color,
    val positive: Color,
    val destructive: Color,
    val callActionSurface: Color,
)

object NqrbSpacing {
    val Xs = 6.dp
    val Sm = 10.dp
    val Md = 16.dp
    val Lg = 24.dp
    val Xl = 32.dp
}

private val LightTokens = NqrbColors(
    background = Color(0xFFF4F7F3),
    backgroundGlow = Color(0xFFE0F5E9),
    surface = Color(0xFFFFFFFF),
    elevatedSurface = Color(0xFFF0F5F1),
    textPrimary = Color(0xFF12201A),
    textSecondary = Color(0xFF5D6B64),
    border = Color(0xFFD6E0D9),
    accent = Color(0xFF087F5B),
    accentSoft = Color(0xFFD5F3E4),
    positive = Color(0xFF15835B),
    destructive = Color(0xFFC63C4A),
    callActionSurface = Color(0xFF0A8F67),
)

private val DarkTokens = NqrbColors(
    background = Color(0xFF07120E),
    backgroundGlow = Color(0xFF10352A),
    surface = Color(0xFF101F19),
    elevatedSurface = Color(0xFF172A22),
    textPrimary = Color(0xFFF1F8F4),
    textSecondary = Color(0xFFAABBB2),
    border = Color(0xFF294138),
    accent = Color(0xFF62D8AA),
    accentSoft = Color(0xFF173F31),
    positive = Color(0xFF6BE0AF),
    destructive = Color(0xFFFF8690),
    callActionSurface = Color(0xFF36C893),
)

val LocalNqrbColors = staticCompositionLocalOf { DarkTokens }

private val NqrbTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 42.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 31.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 25.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun NqrbTheme(appearance: ResolvedAppearance, content: @Composable () -> Unit) {
    val colors = if (appearance == ResolvedAppearance.Dark) DarkTokens else LightTokens
    val materialColors = if (appearance == ResolvedAppearance.Dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.background,
            secondary = colors.positive,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            error = colors.destructive,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = Color.White,
            secondary = colors.positive,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            error = colors.destructive,
        )
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalNqrbColors provides colors) {
        MaterialTheme(
            colorScheme = materialColors,
            typography = NqrbTypography,
            content = content,
        )
    }
}
