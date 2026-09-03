package com.enpo.connect

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.botglobal.mobile.platform.appearance.ResolvedAppearance
import com.botglobal.mobile.platform.device.AndroidUuidInstallationIdGenerator
import com.botglobal.mobile.platform.device.AndroidRuntimePermissionController
import com.botglobal.mobile.platform.device.InstallationIdentity
import com.botglobal.mobile.platform.device.PermissionKind
import com.botglobal.mobile.platform.device.PreferenceInstallationIdStore
import com.botglobal.mobile.platform.networking.NetworkEnvironment
import com.botglobal.mobile.platform.networking.createNetworkClient
import com.botglobal.mobile.platform.invitations.QrScanResult
import com.botglobal.mobile.platform.invitations.QrScannerCapability
import com.botglobal.mobile.platform.notifications.AndroidDeviceCredentialStorageConfig
import com.botglobal.mobile.platform.notifications.AndroidSecureMobileDeviceCredentialVault
import com.botglobal.mobile.platform.preferences.AndroidPreferenceStore
import com.enpo.connect.app.EnpoConnectApp
import com.enpo.connect.app.network.EnpoConnectV2PairingApi
import com.enpo.connect.app.network.EnpoNetworkConfiguration
import com.enpo.connect.app.pairing.EnpoPairingCoordinator
import com.enpo.connect.app.pairing.EnpoPairingDeviceInfo
import com.enpo.connect.app.state.PlatformEnpoDeviceInfrastructure
import com.enpo.connect.app.state.EnpoLegacyStorageCompatibility
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private var qrScanContinuation: CancellableContinuation<QrScanResult>? = null
    private var pairingHttpClient: HttpClient? = null

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val outcome = result.contents
            ?.takeIf(String::isNotBlank)
            ?.let(QrScanResult::Recognized)
            ?: QrScanResult.Cancelled
        val continuation = qrScanContinuation
        qrScanContinuation = null
        continuation?.takeIf { it.isActive }?.resume(outcome)
    }

    private val permissions = AndroidRuntimePermissionController(
        this,
        mapOf(PermissionKind.Camera to listOf(Manifest.permission.CAMERA)),
    )

    private val qrScanner = object : QrScannerCapability {
        override suspend fun scan(prompt: String): QrScanResult =
            suspendCancellableCoroutine { continuation ->
                qrScanContinuation?.cancel()
                qrScanContinuation = continuation
                continuation.invokeOnCancellation {
                    if (qrScanContinuation === continuation) qrScanContinuation = null
                }
                runCatching {
                    qrScanLauncher.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt(prompt)
                            .setBeepEnabled(false)
                            .setOrientationLocked(true),
                    )
                }.onFailure {
                    if (qrScanContinuation === continuation) qrScanContinuation = null
                    if (continuation.isActive) continuation.resume(QrScanResult.Unavailable)
                }
            }

        override fun toString(): String = "AndroidQrScanner(content=<redacted>)"
    }

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
        val networkClient = createNetworkClient(networkConfiguration.clientConfiguration)
            .also { pairingHttpClient = it }
        val runtimeVersion = runtimeVersionName()
        val pairingCoordinator = EnpoPairingCoordinator(
            permissions = permissions,
            scanner = qrScanner,
            client = EnpoConnectV2PairingApi(
                client = networkClient,
                configuration = networkConfiguration,
                installationIdentity = installationIdentity,
                deviceInfo = EnpoPairingDeviceInfo(
                    platform = "android",
                    deviceName = listOf(Build.MANUFACTURER, Build.MODEL)
                        .filter(String::isNotBlank)
                        .joinToString(" ")
                        .ifBlank { "Android device" }
                        .take(120),
                    appVersion = runtimeVersion.takeIf(String::isNotBlank)?.take(50),
                ),
            ),
            credentialVault = credentialVault,
        )

        setContent {
            EnpoConnectApp(
                runtimeVersionName = runtimeVersion,
                preferences = preferences,
                deviceInfrastructure = deviceInfrastructure,
                networkConfiguration = networkConfiguration,
                pairingCoordinator = pairingCoordinator,
                onResolvedAppearanceChanged = ::applySystemBarAppearance,
            )
        }
    }

    override fun onDestroy() {
        qrScanContinuation?.cancel()
        qrScanContinuation = null
        pairingHttpClient?.close()
        pairingHttpClient = null
        super.onDestroy()
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
