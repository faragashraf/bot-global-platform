package com.botglobal.nqrb

import android.app.Application
import com.botglobal.mobile.platform.identity.AndroidSecureSessionVault
import com.botglobal.mobile.platform.notifications.PushRegistrationController
import com.botglobal.mobile.platform.notifications.firebase.AndroidFirebaseMessagingRuntime
import com.botglobal.mobile.platform.notifications.firebase.AndroidPushDeviceInstallation
import com.botglobal.mobile.platform.notifications.firebase.AndroidSecureMobileDeviceCredentialVault
import com.botglobal.mobile.platform.notifications.firebase.FirebaseMessagingRuntimeOwner
import com.botglobal.nqrb.app.data.NqrbPushRegistrationApi
import com.botglobal.nqrb.app.data.createNqrbHttpClient
import com.botglobal.nqrb.calling.NqrbCallRuntime

class NqrbApplication : Application(), FirebaseMessagingRuntimeOwner {
    lateinit var callRuntime: NqrbCallRuntime
        private set
    lateinit var sessionVault: AndroidSecureSessionVault
        private set
    override lateinit var firebaseMessagingRuntime: AndroidFirebaseMessagingRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        sessionVault = AndroidSecureSessionVault(this, "nqrb")
        val pushRegistration = NqrbPushRegistrationApi(
            platformClient = createNqrbHttpClient(),
            apiBaseUrl = BuildConfig.API_BASE_URL,
            sessionVault = sessionVault,
            deviceCredentialVault = AndroidSecureMobileDeviceCredentialVault(this, "nqrb"),
            installation = AndroidPushDeviceInstallation(this, BuildConfig.VERSION_NAME).value,
        )
        firebaseMessagingRuntime = AndroidFirebaseMessagingRuntime(
            context = this,
            registrationController = PushRegistrationController(pushRegistration),
        )
        callRuntime = NqrbCallRuntime(
            application = this,
            apiBaseUrl = BuildConfig.API_BASE_URL,
            sessionVault = sessionVault,
        )
    }
}
