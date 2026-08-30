package com.botglobal.lamma.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object FamilyGamesColors {
    val Night = Color(0xFF15122A)
    val NightSoft = Color(0xFF24203D)
    val Purple = Color(0xFF7857F6)
    val PurpleSoft = Color(0xFFA28BFF)
    val Coral = Color(0xFFFF6B6B)
    val Gold = Color(0xFFFFC857)
    val Mint = Color(0xFF52D9A7)
    val Cream = Color(0xFFFFF8EA)
    val Muted = Color(0xFFB8B2C9)
}

object FamilyGamesSpacing {
    val Xs = 6.dp
    val Sm = 10.dp
    val Md = 16.dp
    val Lg = 24.dp
    val Xl = 32.dp
}

private val ColorScheme = darkColorScheme(
    primary = FamilyGamesColors.PurpleSoft,
    onPrimary = FamilyGamesColors.Night,
    secondary = FamilyGamesColors.Gold,
    onSecondary = FamilyGamesColors.Night,
    tertiary = FamilyGamesColors.Mint,
    background = FamilyGamesColors.Night,
    onBackground = FamilyGamesColors.Cream,
    surface = FamilyGamesColors.NightSoft,
    onSurface = FamilyGamesColors.Cream,
    error = FamilyGamesColors.Coral,
)

@Composable
fun FamilyGamesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ColorScheme, content = content)
}
