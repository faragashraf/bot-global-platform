package com.ashraffarag.sentricam.device.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandaloneRuntimeArchitectureTest {
    @Test
    fun connectivityServiceRefusesSignalROwnershipForStandaloneMode() {
        val service = source(
            "src/main/java/com/ashraffarag/sentricam/device/android/DeviceConnectivityService.kt",
        )
        val startConnectivity = functionBody(service, "private fun startConnectivity()")

        assertTrue(startConnectivity.contains("CameraOperatingMode.Standalone"))
        assertTrue(startConnectivity.contains("stopSignalRIfOwned()"))
        assertTrue(startConnectivity.indexOf("CameraOperatingMode.Standalone") < startConnectivity.indexOf("runtime.signalR.start"))
    }

    @Test
    fun authenticationFailureRefreshesCredentialsWithoutForgettingPairingOrStoppingMonitoring() {
        val runtime = source(
            "src/main/java/com/ashraffarag/sentricam/device/android/DeviceRuntime.kt",
        )
        val authenticationBranch = runtime.substringAfter("is SignalRState.AuthenticationFailed -> {")
            .substringBefore("is SignalRState.Disconnected")

        assertTrue(authenticationBranch.contains("registration.refreshRejectedCredentials()"))
        assertFalse(authenticationBranch.contains("registration.forgetRegistration()"))
        assertFalse(authenticationBranch.contains("monitoring.stop()"))
    }

    @Test
    fun standaloneSkipsRemoteCameraStatePublishingAndHubTimeVerification() {
        val runtime = source(
            "src/main/java/com/ashraffarag/sentricam/device/android/DeviceRuntime.kt",
        )

        assertTrue(runtime.contains("changed && operatingMode.value is CameraOperatingMode.HubManaged"))
        assertTrue(runtime.contains("fun retryTimeValidation()"))
        assertTrue(runtime.substringAfter("fun retryTimeValidation()")
            .substringBefore("private fun replaceHubTimeVerification")
            .contains("CameraOperatingMode.HubManaged"))
    }

    private fun source(path: String): String = File(path).readText()

    private fun functionBody(source: String, signature: String): String =
        source.substringAfter(signature).substringBefore("\n    private fun", missingDelimiterValue = source)
}
