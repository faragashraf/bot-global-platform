package com.enpo.connect

import android.app.Application
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
        firebaseMessagingRuntime = AndroidFirebaseMessagingRuntime(
            context = this,
            registrationController = PushRegistrationController(registration),
            messageHandler = SemanticPushMessageHandler(
                parser = EnpoNotificationContract.parser(),
                inbox = notificationInbox,
                presenter = EnpoAndroidNotificationPresenter(this, preferences),
            ),
        )
        activatePushIfPaired()
    }

    fun activatePushIfPaired() {
        applicationScope.launch {
            if (credentialVault.availability() == MobileDeviceCredentialAvailability.Available) {
                runCatching { firebaseMessagingRuntime.activate() }
            }
        }
    }
}
