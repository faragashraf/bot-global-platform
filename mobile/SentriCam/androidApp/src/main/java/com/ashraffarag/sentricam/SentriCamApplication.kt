package com.ashraffarag.sentricam

import android.app.Application
import com.ashraffarag.sentricam.device.android.DeviceRuntime
import com.ashraffarag.sentricam.device.android.RuntimeLifecycleDiagnostics
import com.ashraffarag.sentricam.localization.android.AndroidAppLocaleApplier
import com.ashraffarag.sentricam.localization.android.SharedPreferencesAppLanguagePreference
import com.ashraffarag.sentricam.localization.domain.AppLanguageController

class SentriCamApplication : Application() {
    lateinit var deviceRuntime: DeviceRuntime
        private set
    lateinit var languageController: AppLanguageController
        private set

    override fun onCreate() {
        super.onCreate()
        languageController = AppLanguageController(
            SharedPreferencesAppLanguagePreference(this),
            AndroidAppLocaleApplier,
        ).also(AppLanguageController::restorePersistedLanguage)
        RuntimeLifecycleDiagnostics.install(this)
        deviceRuntime = DeviceRuntime(this)
    }

    override fun onTerminate() {
        deviceRuntime.close()
        super.onTerminate()
    }
}
