package com.botglobal.nqrb

import android.Manifest
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.botglobal.mobile.platform.appearance.ResolvedAppearance
import com.botglobal.mobile.platform.contacts.AndroidContactsGateway
import com.botglobal.mobile.platform.contacts.ContactsController
import com.botglobal.mobile.platform.device.AndroidRuntimePermissionController
import com.botglobal.mobile.platform.device.PermissionKind
import com.botglobal.mobile.platform.identity.AndroidGoogleCredentialProvider
import com.botglobal.mobile.platform.identity.AndroidSecureSessionVault
import com.botglobal.mobile.platform.identity.FederatedIdentityController
import com.botglobal.nqrb.app.data.NqrbIdentityApi
import com.botglobal.nqrb.app.data.createNqrbHttpClient
import com.botglobal.nqrb.app.state.NqrbAppState
import com.botglobal.nqrb.app.ui.NqrbApp

class MainActivity : ComponentActivity() {
    private val permissionController = AndroidRuntimePermissionController(
        activity = this,
        permissions = mapOf(
            PermissionKind.Contacts to listOf(Manifest.permission.READ_CONTACTS),
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sessionVault = AndroidSecureSessionVault(applicationContext, "nqrb")
        val appState = NqrbAppState(
            identity = FederatedIdentityController(
                credentials = AndroidGoogleCredentialProvider(this, BuildConfig.GOOGLE_SERVER_CLIENT_ID),
                gateway = NqrbIdentityApi(
                    platformClient = createNqrbHttpClient(),
                    apiBaseUrl = BuildConfig.API_BASE_URL,
                    vault = sessionVault,
                ),
            ),
            contacts = ContactsController(
                permissions = permissionController,
                gateway = AndroidContactsGateway(applicationContext),
            ),
        )
        setContent {
            NqrbApp(
                appState = appState,
                onResolvedAppearanceChanged = ::applySystemBarAppearance,
            )
        }
    }

    private fun applySystemBarAppearance(appearance: ResolvedAppearance) {
        val style = when (appearance) {
            ResolvedAppearance.Light -> SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            ResolvedAppearance.Dark -> SystemBarStyle.dark(Color.TRANSPARENT)
        }
        enableEdgeToEdge(
            statusBarStyle = style,
            navigationBarStyle = style,
        )
    }
}
