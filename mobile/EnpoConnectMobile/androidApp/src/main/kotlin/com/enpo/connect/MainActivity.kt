package com.enpo.connect

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.botglobal.mobile.platform.appearance.ResolvedAppearance
import com.botglobal.mobile.platform.preferences.AndroidPreferenceStore
import com.enpo.connect.app.EnpoConnectApp
import com.enpo.connect.app.state.EnpoLegacyStorageCompatibility

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val preferences = AndroidPreferenceStore(
            applicationContext,
            EnpoLegacyStorageCompatibility.ApplicationPreferencesFile,
        )

        setContent {
            EnpoConnectApp(
                runtimeVersionName = runtimeVersionName(),
                preferences = preferences,
                onResolvedAppearanceChanged = ::applySystemBarAppearance,
            )
        }
    }

    private fun applySystemBarAppearance(appearance: ResolvedAppearance) {
        val style = when (appearance) {
            ResolvedAppearance.Light -> SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            ResolvedAppearance.Dark -> SystemBarStyle.dark(Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    private fun runtimeVersionName(): String = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")
}
