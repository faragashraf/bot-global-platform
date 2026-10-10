package com.botglobal.nqrb

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.media.RingtoneManager
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.app.NotificationManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.botglobal.mobile.platform.appearance.ResolvedAppearance
import com.botglobal.mobile.platform.appearance.AppearanceController
import com.botglobal.mobile.platform.appearance.AppearancePreference
import com.botglobal.mobile.platform.calling.CallingDirectoryController
import com.botglobal.mobile.platform.device.AndroidRuntimePermissionController
import com.botglobal.mobile.platform.device.PermissionKind
import com.botglobal.mobile.platform.identity.AndroidGoogleCredentialProvider
import com.botglobal.mobile.platform.identity.FederatedIdentityController
import com.botglobal.mobile.platform.preferences.AndroidPreferenceStore
import com.botglobal.mobile.platform.reviews.AndroidPlayReviewPromptLauncher
import com.botglobal.mobile.platform.reviews.ReviewCoordinator
import com.botglobal.nqrb.app.state.NqrbContactBookController
import com.botglobal.nqrb.app.state.NqrbAppState
import com.botglobal.nqrb.app.state.NqrbPlayReviewPolicy
import com.botglobal.nqrb.app.state.NqrbRingtone
import com.botglobal.nqrb.app.state.NqrbRingtoneSettings
import com.botglobal.nqrb.app.ui.NqrbApp
import com.botglobal.nqrb.calling.NqrbOngoingCallService
import com.botglobal.nqrb.calling.NqrbRingtonePlayer

class MainActivity : ComponentActivity() {
    private lateinit var appState: NqrbAppState
    private var isForeground = false
    @Volatile private var hasValidatedNetwork = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (validated && !hasValidatedNetwork) {
                hasValidatedNetwork = true
                runOnUiThread { if (isForeground) appState.onForeground() }
            } else if (!validated) {
                hasValidatedNetwork = false
            }
        }

        override fun onLost(network: Network) {
            hasValidatedNetwork = false
        }
    }
    private var notificationsEnabled by mutableStateOf(true)
    private val ringtonePreview by lazy { NqrbRingtonePlayer(this) }
    private val phoneRingtonePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val selected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            selected?.let { uri ->
                val name = runCatching { RingtoneManager.getRingtone(this, uri)?.getTitle(this) }.getOrNull()
                appState.ringtone.selectDeviceTone(uri.toString(), name)
            }
        }
    }
    private val notificationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationsEnabled = callNotificationsEnabled() }
    private val permissionController = AndroidRuntimePermissionController(
        activity = this,
        permissions = mapOf(
            PermissionKind.Microphone to listOf(Manifest.permission.RECORD_AUDIO),
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        configureIncomingCallPresentation(intent)
        enableEdgeToEdge()
        val nqrbApplication = application as NqrbApplication
        val sessionVault = nqrbApplication.sessionVault
        val appearanceStore = AndroidPreferenceStore(this, "nqrb_appearance")
        val reviewStore = AndroidPreferenceStore(this, "nqrb_review")
        val chatPreferenceStore = AndroidPreferenceStore(this, "nqrb_chat")
        val savedAppearance = AppearancePreference.entries.firstOrNull {
            it.name == appearanceStore.string(AppearancePreferenceKey)
        } ?: AppearancePreference.Light
        appState = NqrbAppState(
            appearance = AppearanceController(
                initialPreference = savedAppearance,
                onPreferenceSelected = { appearanceStore.putString(AppearancePreferenceKey, it.name) },
            ),
            identity = FederatedIdentityController(
                credentials = AndroidGoogleCredentialProvider(this, BuildConfig.GOOGLE_SERVER_CLIENT_ID),
                gateway = nqrbApplication.identityApi,
            ),
            calling = nqrbApplication.callRuntime.session,
            callingDirectory = CallingDirectoryController(nqrbApplication.callingDirectoryApi),
            contactBook = NqrbContactBookController(nqrbApplication.contactBookApi),
            ringtone = NqrbRingtoneSettings(
                AndroidPreferenceStore(this, NqrbOngoingCallService.RingtonePreferences),
            ),
            callActivity = nqrbApplication.callActivity,
            chat = nqrbApplication.chat,
            chatVoiceRecorder = nqrbApplication.chatVoiceRecorder,
            chatVoicePlayer = nqrbApplication.chatVoicePlayer,
            notificationInbox = nqrbApplication.notificationInbox,
            push = nqrbApplication.firebaseMessagingRuntime,
            accountDeletion = nqrbApplication.accountDeletionApi,
            accountProfile = nqrbApplication.identityApi,
            localAccountDataCleaner = nqrbApplication.localAccountDataCleaner,
            permissions = permissionController,
            openMicrophoneSettings = {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            },
            reviews = ReviewCoordinator(
                preferenceStore = reviewStore,
                storageKey = "play_review_policy",
                policy = NqrbPlayReviewPolicy,
                launcher = AndroidPlayReviewPromptLauncher { if (!isFinishing && !isDestroyed) this else null },
                nowMillis = System::currentTimeMillis,
            ),
            updatePolicy = nqrbApplication.identityApi,
            chatPreferences = chatPreferenceStore,
            currentVersion = BuildConfig.VERSION_NAME,
            platform = "android",
        )
        setContent {
            NqrbApp(
                appState = appState,
                onResolvedAppearanceChanged = ::applySystemBarAppearance,
                onShareInvite = ::shareInvite,
                onPreviewRingtone = { ringtonePreview.play(it, looping = false, deviceToneUri = appState.ringtone.deviceToneUri) },
                onChoosePhoneRingtone = ::choosePhoneRingtone,
                onNotificationPermissionNeeded = ::requestNotificationPermission,
                notificationsEnabled = notificationsEnabled,
                onOpenNotificationSettings = ::openNotificationSettings,
                onOpenStoreDestination = ::openStoreDestination,
                callTime = ::androidCallTime,
            )
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        handleInviteIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        configureIncomingCallPresentation(intent)
        handleInviteIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        notificationsEnabled = callNotificationsEnabled()
        appState.onForeground()
    }

    override fun onPause() {
        isForeground = false
        appState.onBackground()
        super.onPause()
    }

    override fun onDestroy() {
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        ringtonePreview.stop()
        super.onDestroy()
    }

    private fun shareInvite(message: String): Boolean = runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        startActivity(Intent.createChooser(send, null))
        true
    }.getOrDefault(false)

    private companion object {
        const val AppearancePreferenceKey = "appearance_preference"
    }

    private fun choosePhoneRingtone() {
        val existing = appState.ringtone.deviceToneUri?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val picker = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
        }
        phoneRingtonePicker.launch(picker)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) return
        val preferences = getSharedPreferences("nqrb_notification_permission", MODE_PRIVATE)
        if (preferences.getBoolean("prompted", false)) return
        preferences.edit().putBoolean("prompted", true).apply()
        notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openNotificationSettings() {
        val notifications = getSystemService(NotificationManager::class.java)
        val incomingChannelDisabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            notifications.getNotificationChannel(NqrbOngoingCallService.IncomingChannelId)?.importance ==
                NotificationManager.IMPORTANCE_NONE
        val settingsIntent = if (incomingChannelDisabled && notifications.areNotificationsEnabled()) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, NqrbOngoingCallService.IncomingChannelId)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        startActivity(settingsIntent)
    }

    private fun openStoreDestination(destination: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(destination)))
        }
    }

    private fun callNotificationsEnabled(): Boolean {
        val notifications = getSystemService(NotificationManager::class.java)
        return notifications.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                notifications.getNotificationChannel(NqrbOngoingCallService.IncomingChannelId)?.importance !=
                    NotificationManager.IMPORTANCE_NONE)
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

    private fun handleInviteIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "nqrb" && uri.host == "invite") {
            uri.pathSegments.firstOrNull()?.let(appState::handleNqrbInviteLink)
        }
    }

    private fun configureIncomingCallPresentation(intent: Intent?) {
        val showOverLockScreen =
            intent?.getBooleanExtra(NqrbOngoingCallService.ExtraShowOverLockScreen, false) == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(showOverLockScreen)
            setTurnScreenOn(showOverLockScreen)
        } else {
            @Suppress("DEPRECATION")
            if (showOverLockScreen) {
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                )
            } else {
                window.clearFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                )
            }
        }
    }
}
