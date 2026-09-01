package com.botglobal.nqrb

import android.Manifest
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.botglobal.mobile.platform.appearance.ResolvedAppearance
import com.botglobal.mobile.platform.contacts.AndroidContactsGateway
import com.botglobal.mobile.platform.contacts.ContactsController
import com.botglobal.mobile.platform.calling.CallingDirectoryController
import com.botglobal.mobile.platform.device.AndroidRuntimePermissionController
import com.botglobal.mobile.platform.device.PermissionKind
import com.botglobal.mobile.platform.identity.AndroidGoogleCredentialProvider
import com.botglobal.mobile.platform.identity.FederatedIdentityController
import com.botglobal.nqrb.app.state.NqrbAppState
import com.botglobal.nqrb.app.ui.NqrbApp

class MainActivity : ComponentActivity() {
    private val permissionController = AndroidRuntimePermissionController(
        activity = this,
        permissions = mapOf(
            PermissionKind.Contacts to listOf(Manifest.permission.READ_CONTACTS),
            PermissionKind.Microphone to buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val nqrbApplication = application as NqrbApplication
        val sessionVault = nqrbApplication.sessionVault
        val appState = NqrbAppState(
            identity = FederatedIdentityController(
                credentials = AndroidGoogleCredentialProvider(this, BuildConfig.GOOGLE_SERVER_CLIENT_ID),
                gateway = nqrbApplication.identityApi,
            ),
            contacts = ContactsController(
                permissions = permissionController,
                gateway = AndroidContactsGateway(applicationContext),
            ),
            calling = nqrbApplication.callRuntime.session,
            callingDirectory = CallingDirectoryController(nqrbApplication.callingDirectoryApi),
            callActivity = nqrbApplication.callActivity,
            push = nqrbApplication.firebaseMessagingRuntime,
            permissions = permissionController,
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
