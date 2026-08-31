package com.botglobal.nqrb

import android.app.Application
import com.botglobal.mobile.platform.identity.AndroidSecureSessionVault
import com.botglobal.nqrb.calling.NqrbCallRuntime

class NqrbApplication : Application() {
    lateinit var callRuntime: NqrbCallRuntime
        private set
    lateinit var sessionVault: AndroidSecureSessionVault
        private set

    override fun onCreate() {
        super.onCreate()
        sessionVault = AndroidSecureSessionVault(this, "nqrb")
        callRuntime = NqrbCallRuntime(
            application = this,
            apiBaseUrl = BuildConfig.API_BASE_URL,
            sessionVault = sessionVault,
        )
    }
}
