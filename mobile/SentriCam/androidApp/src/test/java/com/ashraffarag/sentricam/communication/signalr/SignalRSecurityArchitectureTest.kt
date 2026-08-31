package com.ashraffarag.sentricam.communication.signalr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalRSecurityArchitectureTest {
    @Test
    fun transportAndConnectionManagerContainNoActivityFragmentOrComposableDependency() {
        val types = listOf(
            SignalRClient::class.java,
            MicrosoftSignalRClient::class.java,
            SignalRConnectionManager::class.java,
            SignalRStateMachine::class.java,
        )
        val dependencies = types.flatMap { type ->
            type.declaredFields.map { it.type.name } +
                type.declaredMethods.flatMap { method ->
                    listOf(method.returnType.name) + method.parameterTypes.map(Class<*>::getName)
                }
        }

        assertFalse(dependencies.any { dependency ->
            dependency.contains("Activity") ||
                dependency.contains("Fragment") ||
                dependency.contains("Composable") ||
                dependency.startsWith("android.view")
        })
    }

    @Test
    fun deviceConnectivityServiceOwnsConnectionAndMonitoringStopDoesNotStopTransport() {
        val service = source(
            "src/main/java/com/ashraffarag/sentricam/device/android/DeviceConnectivityService.kt",
        )
        val monitoringStop = service.substringAfter("private fun stopMonitoring()")
            .substringBefore("private fun restartMonitoring()")

        assertTrue(service.contains("runtime.remoteMonitoringCommands,"))
        assertTrue(service.contains("runtime.cameraControl,"))
        assertTrue(service.contains("runtime.signalR.stop()"))
        assertFalse(monitoringStop.contains("runtime.signalR.stop()"))
        assertFalse(monitoringStop.contains("removeForegroundAndStop()"))
        assertTrue(monitoringStop.contains("updateForegroundForCurrentOwnership()"))
        assertFalse(service.contains("Heartbeat("))
    }

    @Test
    fun settingsAndLoggersNeverReferenceCredentialSecrets() {
        val settings = source(
            "src/main/java/com/ashraffarag/sentricam/settings/android/AppSettingsActivity.kt",
        )
        val logger = source(
            "src/main/java/com/ashraffarag/sentricam/communication/signalr/android/AndroidSignalRLogger.kt",
        )

        assertFalse(
            "Settings references raw access tokens",
            Regex("accessToken(?!Expires)", RegexOption.IGNORE_CASE).containsMatchIn(settings),
        )
        listOf("refreshToken", "Authorization", "JWT").forEach { secret ->
            assertFalse("Settings references $secret", settings.contains(secret, ignoreCase = true))
        }
        listOf("accessToken", "refreshToken", "Authorization", "JWT").forEach { secret ->
            assertFalse("SignalR logger references $secret", logger.contains(secret, ignoreCase = true))
        }
    }

    @Test
    fun api23ReleaseHttpsAndDebugCleartextPoliciesRemainIntact() {
        val build = source("build.gradle.kts")
        val mainManifest = source("src/main/AndroidManifest.xml")
        val debugManifest = source("src/debug/AndroidManifest.xml")

        assertTrue(build.contains("minSdk = 23"))
        assertTrue(mainManifest.contains("android:usesCleartextTraffic=\"false\""))
        assertTrue(debugManifest.contains("android:usesCleartextTraffic=\"true\""))
        assertTrue(mainManifest.contains("android:foregroundServiceType=\"connectedDevice|camera|microphone\""))
        assertTrue(mainManifest.contains("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"))
        assertTrue(mainManifest.contains("android.permission.CHANGE_NETWORK_STATE"))
    }

    @Test
    fun oneForegroundServiceDynamicallySeparatesConnectivityAndCameraTypes() {
        val manifest = source("src/main/AndroidManifest.xml")
        val service = source(
            "src/main/java/com/ashraffarag/sentricam/device/android/DeviceConnectivityService.kt",
        )

        assertEquals(1, Regex("<service\\b").findAll(manifest).count())
        assertTrue(service.contains("FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE"))
        assertTrue(service.contains("if (monitoringActive)"))
        assertTrue(service.contains("FOREGROUND_SERVICE_TYPE_CAMERA"))
        assertTrue(service.contains("audioEnabled && hasPermission(Manifest.permission.RECORD_AUDIO)"))
    }

    @Test
    fun remoteCommandTransportStillContainsNoDeviceBusinessHandler() {
        val signalRDirectory = File("src/main/java/com/ashraffarag/sentricam/communication/signalr")
        val source = signalRDirectory.walkTopDown()
            .filter(File::isFile)
            .joinToString("\n", transform = File::readText)

        assertFalse(source.contains("DeviceCommandHandler"))
        assertFalse(source.contains("StartRecording"))
        assertFalse(source.contains("DefaultDeviceCommandHandler"))
    }

    private fun source(relativePath: String): String = File(relativePath).readText()
}
