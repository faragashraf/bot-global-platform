package com.botglobal.nqrb

import android.app.Application
import android.util.Log
import com.botglobal.mobile.platform.identity.AndroidSecureSessionVault
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.notifications.PushRegistrationController
import com.botglobal.mobile.platform.notifications.AndroidPreferenceNotificationInbox
import com.botglobal.mobile.platform.notifications.firebase.AndroidFirebaseMessagingRuntime
import com.botglobal.mobile.platform.notifications.firebase.AndroidPushDeviceInstallation
import com.botglobal.mobile.platform.notifications.firebase.FirebaseMessageDeliveryPolicy
import com.botglobal.mobile.platform.notifications.firebase.AndroidSecureMobileDeviceCredentialVault
import com.botglobal.mobile.platform.notifications.firebase.FirebaseMessagingRuntimeOwner
import com.botglobal.nqrb.app.data.NqrbPushRegistrationApi
import com.botglobal.nqrb.app.data.NqrbIdentityApi
import com.botglobal.nqrb.app.data.NqrbCallingDirectoryApi
import com.botglobal.nqrb.app.data.NqrbContactBookApi
import com.botglobal.nqrb.app.data.createNqrbHttpClient
import com.botglobal.nqrb.calling.NqrbCallRuntime
import com.botglobal.nqrb.calling.AndroidNqrbGeneralNotificationPresenter
import com.botglobal.nqrb.calling.NqrbPushMessageHandler
import com.botglobal.nqrb.calling.AndroidPendingCallUsageStore
import com.botglobal.nqrb.app.data.NqrbCallActivityApi
import com.botglobal.nqrb.app.data.NqrbAccountDeletionApi
import com.botglobal.nqrb.app.state.NqrbLocalAccountDataCleaner
import com.botglobal.mobile.platform.calling.CallActivityController
import com.botglobal.nqrb.calling.NqrbOngoingCallService
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class NqrbApplication : Application(), FirebaseMessagingRuntimeOwner {
    lateinit var callRuntime: NqrbCallRuntime
        private set
    lateinit var sessionVault: AndroidSecureSessionVault
        private set
    lateinit var identityApi: NqrbIdentityApi
        private set
    lateinit var callingDirectoryApi: NqrbCallingDirectoryApi
        private set
    lateinit var contactBookApi: NqrbContactBookApi
        private set
    lateinit var callActivity: CallActivityController
        private set
    lateinit var accountDeletionApi: NqrbAccountDeletionApi
        private set
    lateinit var localAccountDataCleaner: NqrbLocalAccountDataCleaner
        private set
    lateinit var notificationInbox: AndroidPreferenceNotificationInbox
        private set
    override lateinit var firebaseMessagingRuntime: AndroidFirebaseMessagingRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        sessionVault = AndroidSecureSessionVault(this, "nqrb")
        identityApi = NqrbIdentityApi(createNqrbHttpClient(), BuildConfig.API_BASE_URL, sessionVault) {
            Log.w("NqrbIdentity", it)
        }
        accountDeletionApi = NqrbAccountDeletionApi(
            createNqrbHttpClient(),
            BuildConfig.API_BASE_URL,
            sessionVault,
        )
        callingDirectoryApi = NqrbCallingDirectoryApi(
            createNqrbHttpClient(),
            BuildConfig.API_BASE_URL,
            sessionVault,
        )
        contactBookApi = NqrbContactBookApi(
            createNqrbHttpClient(),
            BuildConfig.API_BASE_URL,
            sessionVault,
        )
        val pendingCallUsageStore = AndroidPendingCallUsageStore(this)
        notificationInbox = AndroidPreferenceNotificationInbox(this, "nqrb_notifications")
        callActivity = CallActivityController(
            NqrbCallActivityApi(createNqrbHttpClient(), BuildConfig.API_BASE_URL, sessionVault),
            pendingCallUsageStore,
        )
        val deviceCredentialVault = AndroidSecureMobileDeviceCredentialVault(this, "nqrb")
        val pushRegistration = NqrbPushRegistrationApi(
            platformClient = createNqrbHttpClient(),
            apiBaseUrl = BuildConfig.API_BASE_URL,
            sessionVault = sessionVault,
            deviceCredentialVault = deviceCredentialVault,
            installation = AndroidPushDeviceInstallation(this, BuildConfig.VERSION_NAME).value,
        )
        callRuntime = NqrbCallRuntime(
            application = this,
            apiBaseUrl = BuildConfig.API_BASE_URL,
            sessionVault = sessionVault,
            restoreSession = {
                val stored = sessionVault.restore()
                when {
                    stored == null -> false
                    stored.accessExpiresSoon() -> identityApi.restore() != null
                    else -> true
                }
            },
        )
        firebaseMessagingRuntime = AndroidFirebaseMessagingRuntime(
            context = this,
            registrationController = PushRegistrationController(pushRegistration),
            messageHandler = NqrbPushMessageHandler(
                callRuntime,
                AndroidNqrbGeneralNotificationPresenter(this, notificationInbox),
            ),
            messageDeliveryPolicy = FirebaseMessageDeliveryPolicy(blockOnMessage = true),
        )
        localAccountDataCleaner = NqrbLocalAccountDataCleaner {
            runCatching { sessionVault.clear() }
            runCatching { deviceCredentialVault.clear() }
            runCatching { pendingCallUsageStore.clearAll() }
            runCatching { notificationInbox.clearAll() }
            NqrbOngoingCallService.clearStoredPresentation(this)
        }
    }
}

private fun MobileSession.accessExpiresSoon(): Boolean = runCatching {
    Instant.parse(accessExpiresAtUtc) <= Clock.System.now() + 1.minutes
}.getOrDefault(true)
