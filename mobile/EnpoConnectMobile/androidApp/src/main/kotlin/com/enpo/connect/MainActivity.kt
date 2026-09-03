package com.enpo.connect

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.botglobal.mobile.platform.appearance.ResolvedAppearance
import com.botglobal.mobile.platform.device.AndroidUuidInstallationIdGenerator
import com.botglobal.mobile.platform.device.InstallationIdentity
import com.botglobal.mobile.platform.device.PreferenceInstallationIdStore
import com.botglobal.mobile.platform.networking.NetworkEnvironment
import com.botglobal.mobile.platform.notifications.AndroidDeviceCredentialStorageConfig
import com.botglobal.mobile.platform.notifications.AndroidSecureMobileDeviceCredentialVault
import com.botglobal.mobile.platform.preferences.AndroidPreferenceStore
import com.enpo.connect.app.EnpoConnectApp
import com.enpo.connect.app.network.EnpoNetworkConfiguration
import com.enpo.connect.app.state.PlatformEnpoDeviceInfrastructure
import com.enpo.connect.app.state.EnpoLegacyStorageCompatibility

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val preferences = AndroidPreferenceStore(
            applicationContext,
            EnpoLegacyStorageCompatibility.ApplicationPreferencesFile,
        )
        val installationPreferences = AndroidPreferenceStore(
            applicationContext,
            EnpoLegacyStorageCompatibility.InstallationPreferencesFile,
        )
        val installationIdentity = InstallationIdentity(
            PreferenceInstallationIdStore(
                installationPreferences,
                EnpoLegacyStorageCompatibility.InstallationIdKey,
            ),
            AndroidUuidInstallationIdGenerator,
        )
        val credentialVault = AndroidSecureMobileDeviceCredentialVault(
            applicationContext,
            AndroidDeviceCredentialStorageConfig(
                preferencesFile = EnpoLegacyStorageCompatibility.DevicePreferencesFile,
                deviceIdKey = EnpoLegacyStorageCompatibility.DeviceIdKey,
                credentialPayloadKey = EnpoLegacyStorageCompatibility.DeviceCredentialPayloadKey,
                credentialIvKey = EnpoLegacyStorageCompatibility.DeviceCredentialIvKey,
                keyAlias = EnpoLegacyStorageCompatibility.AndroidKeystoreAlias,
            ),
        )
        val deviceInfrastructure = PlatformEnpoDeviceInfrastructure(
            installationIdentity,
            credentialVault,
        )
        val networkConfiguration = EnpoNetworkConfiguration.from(
            BuildConfig.PUBLIC_BASE_URL,
            when (BuildConfig.NETWORK_ENVIRONMENT) {
                "production" -> NetworkEnvironment.Production
                else -> NetworkEnvironment.Development
            },
        )

        setContent {
            EnpoConnectApp(
                runtimeVersionName = runtimeVersionName(),
                preferences = preferences,
                deviceInfrastructure = deviceInfrastructure,
                networkConfiguration = networkConfiguration,
                onResolvedAppearanceChanged = ::applySystemBarAppearance,
            )
        }
    }

    private fun applySystemBarAppearance(appearance: ResolvedAppearance) {
        val style = when (appearance) {
            ResolvedAppearance.Light -> SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            ResolvedAppearance.Dark -> SystemBarStyle.dark(Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    private fun runtimeVersionName(): String = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")
}
