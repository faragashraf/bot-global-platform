package com.enpo.connect

import android.app.Application
import android.app.NotificationManager
import com.botglobal.mobile.platform.networking.NetworkEnvironment
import com.botglobal.mobile.platform.networking.createNetworkClient
import com.botglobal.mobile.platform.notifications.AndroidDeviceCredentialStorageConfig
import com.botglobal.mobile.platform.notifications.AndroidPreferenceNotificationInbox
import com.botglobal.mobile.platform.notifications.AndroidSecureMobileDeviceCredentialVault
import com.botglobal.mobile.platform.notifications.MobileDeviceCredentialAvailability
import com.botglobal.mobile.platform.notifications.PushRegistrationController
import com.botglobal.mobile.platform.notifications.SemanticPushMessageHandler
import com.botglobal.mobile.platform.notifications.firebase.AndroidFirebaseMessagingRuntime
import com.botglobal.mobile.platform.notifications.firebase.FirebaseMessagingRuntimeOwner
import com.botglobal.mobile.platform.preferences.AndroidPreferenceStore
import com.enpo.connect.app.network.EnpoNetworkConfiguration
import com.enpo.connect.app.network.EnpoPushRegistrationApi
import com.enpo.connect.app.notifications.EnpoNotificationContract
import com.enpo.connect.app.notifications.EnpoPairedPushGate
import com.enpo.connect.app.network.EnpoDeviceUnpairApi
import com.enpo.connect.app.pairing.EnpoDeviceUnpairCoordinator
import com.enpo.connect.app.state.EnpoLegacyStorageCompatibility
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EnpoApplication : Application(), FirebaseMessagingRuntimeOwner {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var preferences: AndroidPreferenceStore
        private set
    lateinit var credentialVault: AndroidSecureMobileDeviceCredentialVault
        private set
    lateinit var notificationInbox: AndroidPreferenceNotificationInbox
        private set
    lateinit var networkConfiguration: EnpoNetworkConfiguration
        private set
    override lateinit var firebaseMessagingRuntime: AndroidFirebaseMessagingRuntime
        private set
    lateinit var pairedPushGate: EnpoPairedPushGate
        private set
    lateinit var unpairCoordinator: EnpoDeviceUnpairCoordinator
        private set

    private lateinit var pushHttpClient: HttpClient

    override fun onCreate() {
        super.onCreate()
        preferences = AndroidPreferenceStore(
            this,
            EnpoLegacyStorageCompatibility.ApplicationPreferencesFile,
        )
        credentialVault = AndroidSecureMobileDeviceCredentialVault(
            this,
            AndroidDeviceCredentialStorageConfig(
                preferencesFile = EnpoLegacyStorageCompatibility.DevicePreferencesFile,
                deviceIdKey = EnpoLegacyStorageCompatibility.DeviceIdKey,
                credentialPayloadKey = EnpoLegacyStorageCompatibility.DeviceCredentialPayloadKey,
                credentialIvKey = EnpoLegacyStorageCompatibility.DeviceCredentialIvKey,
                keyAlias = EnpoLegacyStorageCompatibility.AndroidKeystoreAlias,
            ),
        )
        notificationInbox = AndroidPreferenceNotificationInbox(
            this,
            EnpoNotificationContract.InboxStorageName,
        )
        networkConfiguration = EnpoNetworkConfiguration.from(
            BuildConfig.PUBLIC_BASE_URL,
            if (BuildConfig.NETWORK_ENVIRONMENT == "production") {
                NetworkEnvironment.Production
            } else {
                NetworkEnvironment.Development
            },
        )
        pushHttpClient = createNetworkClient(networkConfiguration.clientConfiguration)
        val registration = EnpoPushRegistrationApi(
            pushHttpClient,
            networkConfiguration,
            credentialVault,
        )
        pairedPushGate = EnpoPairedPushGate(
            preferences = preferences,
            credentialVault = credentialVault,
            delegate = SemanticPushMessageHandler(
                parser = EnpoNotificationContract.parser(),
                inbox = notificationInbox,
                presenter = EnpoAndroidNotificationPresenter(this, preferences),
            ),
        )
        firebaseMessagingRuntime = AndroidFirebaseMessagingRuntime(
            context = this,
            registrationController = PushRegistrationController(registration),
            messageHandler = pairedPushGate,
        )
        unpairCoordinator = EnpoDeviceUnpairCoordinator(
            client = EnpoDeviceUnpairApi(pushHttpClient, networkConfiguration),
            credentialVault = credentialVault,
            clearLocalData = {
                notificationInbox.clearAll()
                getSystemService(NotificationManager::class.java).cancelAll()
                firebaseMessagingRuntime.clearLocalState()
            },
            blockNotifications = pairedPushGate::block,
        )
        activatePushIfPaired()
    }

    fun activatePushIfPaired() {
        applicationScope.launch {
            if (pairedPushGate.isBlocked() &&
                credentialVault.availability() == MobileDeviceCredentialAvailability.Available
            ) {
                // Revocation may have succeeded before a timeout or process death. Retry
                // the idempotent request and local cleanup before permitting delivery.
                runCatching { unpairCoordinator.unpair() }
            }
            if (!pairedPushGate.isBlocked() &&
                credentialVault.availability() == MobileDeviceCredentialAvailability.Available
            ) {
                runCatching { firebaseMessagingRuntime.activate() }
            }
        }
    }

    fun resumePushAfterPairing() {
        applicationScope.launch {
            runCatching {
                pairedPushGate.resume()
                firebaseMessagingRuntime.activate()
            }
        }
    }
}
