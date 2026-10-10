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
    val disabledContent: Color,
    val border: Color,
    val controlOutline: Color,
    val accent: Color,
    val accentSoft: Color,
    val compactCallSurface: Color,
    val positive: Color,
    val destructive: Color,
    val callActionSurface: Color,
    val callActionContent: Color,
)

object NqrbSpacing {
    val Xs = 6.dp
    val Sm = 10.dp
    val Md = 16.dp
    val Lg = 24.dp
    val Xl = 32.dp
}

object NqrbLayout { val ThreadMaxWidth = 720.dp }

private val LightTokens = NqrbColors(
    background = Color(0xFFF8F9FD),
    backgroundGlow = Color(0xFFE7ECFF),
    surface = Color(0xFFFFFFFF),
    elevatedSurface = Color(0xFFF0F3FF),
    textPrimary = Color(0xFF182033),
    textSecondary = Color(0xFF526079),
    disabledContent = Color(0xFF8C95A6),
    border = Color(0xFFDDE3F2),
    controlOutline = Color(0xFF73809A),
    accent = Color(0xFF3D5BD8),
    accentSoft = Color(0xFFE7ECFF),
    compactCallSurface = Color(0xFFDDE6FF),
    positive = Color(0xFF0F8B7C),
    destructive = Color(0xFFB23B48),
    callActionSurface = Color(0xFF3D5BD8),
    callActionContent = Color.White,
)

private val DarkTokens = NqrbColors(
    background = Color(0xFF10131C),
    backgroundGlow = Color(0xFF1E294D),
    surface = Color(0xFF181D2A),
    elevatedSurface = Color(0xFF202842),
    textPrimary = Color(0xFFF3F6FF),
    textSecondary = Color(0xFFC6D0E7),
    disabledContent = Color(0xFF717B92),
    border = Color(0xFF35405D),
    controlOutline = Color(0xFF7582A3),
    accent = Color(0xFFA8B7FF),
    accentSoft = Color(0xFF2A355E),
    compactCallSurface = Color(0xFF26345E),
    positive = Color(0xFF62D6C8),
    destructive = Color(0xFFFFA9AE),
    callActionSurface = Color(0xFFA8B7FF),
    callActionContent = Color(0xFF10131C),
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
            surfaceVariant = colors.elevatedSurface,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.controlOutline,
            primaryContainer = colors.accentSoft,
            onPrimaryContainer = colors.textPrimary,
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
            surfaceVariant = colors.elevatedSurface,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.controlOutline,
            primaryContainer = colors.accentSoft,
            onPrimaryContainer = colors.textPrimary,
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
