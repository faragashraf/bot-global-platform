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
    background = Color(0xFFF7FAF8),
    backgroundGlow = Color(0xFFE8F4ED),
    surface = Color(0xFFFFFFFF),
    elevatedSurface = Color(0xFFF0F6F2),
    textPrimary = Color(0xFF182B22),
    textSecondary = Color(0xFF4D6457),
    disabledContent = Color(0xFF8A9990),
    border = Color(0xFFDCE9E0),
    controlOutline = Color(0xFF73877B),
    accent = Color(0xFF167347),
    accentSoft = Color(0xFFE1F3E8),
    compactCallSurface = Color(0xFFCFEAD9),
    positive = Color(0xFF167347),
    destructive = Color(0xFFB23B48),
    callActionSurface = Color(0xFF167347),
    callActionContent = Color.White,
)

private val DarkTokens = NqrbColors(
    background = Color(0xFF0D1B15),
    backgroundGlow = Color(0xFF173627),
    surface = Color(0xFF16291E),
    elevatedSurface = Color(0xFF1E3528),
    textPrimary = Color(0xFFF1FAF4),
    textSecondary = Color(0xFFC4DACB),
    disabledContent = Color(0xFF718779),
    border = Color(0xFF355542),
    controlOutline = Color(0xFF688675),
    accent = Color(0xFF90E7AC),
    accentSoft = Color(0xFF254632),
    compactCallSurface = Color(0xFF1D3C2A),
    positive = Color(0xFF90E7AC),
    destructive = Color(0xFFFFA9AE),
    callActionSurface = Color(0xFF90E7AC),
    callActionContent = Color(0xFF0D1B15),
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
