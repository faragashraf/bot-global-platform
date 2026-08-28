package com.botglobal.familygames.app

import androidx.compose.ui.window.ComposeUIViewController
import com.botglobal.familygames.app.ui.FamilyGamesApp
import com.botglobal.mobile.platform.device.SemanticHaptics
import com.botglobal.mobile.platform.identity.SessionVault

fun MainViewController(
    apiBaseUrl: String,
    sessionVault: SessionVault,
    haptics: SemanticHaptics,
    appVersion: String,
) = ComposeUIViewController {
    FamilyGamesApp(apiBaseUrl, sessionVault, haptics, appVersion, "ios")
}
