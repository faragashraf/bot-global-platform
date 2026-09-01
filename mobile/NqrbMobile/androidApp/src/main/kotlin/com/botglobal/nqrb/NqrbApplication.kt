package com.botglobal.nqrb

import android.app.Application
import com.botglobal.mobile.platform.identity.AndroidSecureSessionVault
import com.botglobal.mobile.platform.notifications.PushRegistrationController
import com.botglobal.mobile.platform.notifications.firebase.AndroidFirebaseMessagingRuntime
import com.botglobal.mobile.platform.notifications.firebase.AndroidPushDeviceInstallation
import com.botglobal.mobile.platform.notifications.firebase.AndroidSecureMobileDeviceCredentialVault
import com.botglobal.mobile.platform.notifications.firebase.FirebaseMessagingRuntimeOwner
import com.botglobal.nqrb.app.data.NqrbPushRegistrationApi
import com.botglobal.nqrb.app.data.NqrbIdentityApi
import com.botglobal.nqrb.app.data.NqrbCallingDirectoryApi
import com.botglobal.nqrb.app.data.createNqrbHttpClient
import com.botglobal.nqrb.calling.NqrbCallRuntime
import com.botglobal.nqrb.calling.NqrbPushMessageHandler
import com.botglobal.nqrb.calling.AndroidPendingCallUsageStore
import com.botglobal.nqrb.app.data.NqrbCallActivityApi
import com.botglobal.mobile.platform.calling.CallActivityController

class NqrbApplication : Application(), FirebaseMessagingRuntimeOwner {
    lateinit var callRuntime: NqrbCallRuntime
        private set
    lateinit var sessionVault: AndroidSecureSessionVault
        private set
    lateinit var identityApi: NqrbIdentityApi
        private set
    lateinit var callingDirectoryApi: NqrbCallingDirectoryApi
        private set
    lateinit var callActivity: CallActivityController
        private set
    override lateinit var firebaseMessagingRuntime: AndroidFirebaseMessagingRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        sessionVault = AndroidSecureSessionVault(this, "nqrb")
        identityApi = NqrbIdentityApi(createNqrbHttpClient(), BuildConfig.API_BASE_URL, sessionVault)
        callingDirectoryApi = NqrbCallingDirectoryApi(
            createNqrbHttpClient(),
            BuildConfig.API_BASE_URL,
            sessionVault,
        )
        callActivity = CallActivityController(
            NqrbCallActivityApi(createNqrbHttpClient(), BuildConfig.API_BASE_URL, sessionVault),
            AndroidPendingCallUsageStore(this),
        )
        val pushRegistration = NqrbPushRegistrationApi(
            platformClient = createNqrbHttpClient(),
            apiBaseUrl = BuildConfig.API_BASE_URL,
            sessionVault = sessionVault,
            deviceCredentialVault = AndroidSecureMobileDeviceCredentialVault(this, "nqrb"),
            installation = AndroidPushDeviceInstallation(this, BuildConfig.VERSION_NAME).value,
        )
        callRuntime = NqrbCallRuntime(
            application = this,
            apiBaseUrl = BuildConfig.API_BASE_URL,
            sessionVault = sessionVault,
            restoreSession = { identityApi.restore() != null },
        )
        firebaseMessagingRuntime = AndroidFirebaseMessagingRuntime(
            context = this,
            registrationController = PushRegistrationController(pushRegistration),
            messageHandler = NqrbPushMessageHandler(callRuntime),
        )
    }
}
