package com.botglobal.familygames

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import com.botglobal.familygames.app.platform.AndroidSecureSessionVault
import com.botglobal.familygames.app.platform.AndroidSemanticHaptics
import com.botglobal.familygames.app.ui.FamilyGamesApp
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : FragmentActivity() {
    private val foregroundEvents = MutableSharedFlow<Unit>(replay = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vault = AndroidSecureSessionVault(applicationContext)
        val haptics = AndroidSemanticHaptics(applicationContext)
        setContent {
            FamilyGamesApp(
                apiBaseUrl = BuildConfig.API_BASE_URL,
                sessionVault = vault,
                haptics = haptics,
                appVersion = BuildConfig.VERSION_NAME,
                platform = "android",
                openExternalUrl = { destination ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(destination)))
                },
                foregroundEvents = foregroundEvents,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        foregroundEvents.tryEmit(Unit)
    }
}
