package com.ashraffarag.sentricam.device.recovery

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryArchitectureTest {
    @Test
    fun `reboot restore and long lived recovery ownership are declared`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val service = File("src/main/java/com/ashraffarag/sentricam/device/android/DeviceConnectivityService.kt").readText()
        val activity = File("src/main/java/com/ashraffarag/sentricam/MainActivity.kt").readText()
        assertTrue(manifest.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertTrue(manifest.contains("DeviceBootReceiver"))
        assertTrue(manifest.contains("android:stopWithTask=\"false\""))
        assertTrue(manifest.contains("android.intent.action.MY_PACKAGE_REPLACED"))
        assertTrue(service.contains("START_STICKY"))
        assertTrue(service.contains("else -> restoreRuntime()"))
        assertTrue(service.contains("if (registered) startConnectivity()"))
        assertTrue(service.contains("scheduleMonitoringRecovery"))
        assertFalse(service.contains("settings.copy(enabled = false)"))
        assertTrue(activity.contains("deviceRuntime.remoteConnectivity.start()"))
    }

    @Test
    fun `closing the activity does not stop the foreground connectivity owner`() {
        val activity = File("src/main/java/com/ashraffarag/sentricam/MainActivity.kt").readText()
        val service = File("src/main/java/com/ashraffarag/sentricam/device/android/DeviceConnectivityService.kt").readText()
        val onStop = activity.substringAfter("override fun onStop()")
            .substringBefore("override fun onDestroy()")
        val onDestroy = activity.substringAfter("override fun onDestroy()")
            .substringBefore("private fun")

        assertFalse(onStop.contains("remoteConnectivity.stop"))
        assertFalse(onDestroy.contains("remoteConnectivity.stop"))
        assertTrue(service.contains("SignalR and heartbeat ownership live for the lifetime of this service"))
        assertTrue(service.contains("START_STICKY"))
    }

    @Test
    fun `realtime upload and health remain separate lifecycle channels`() {
        val signalR = File("src/main/java/com/ashraffarag/sentricam/communication/signalr/SignalRConnectionManager.kt").readText()
        val uploads = File("src/main/java/com/ashraffarag/sentricam/recording/upload/android/WorkManagerRecordingUploadScheduler.kt").readText()
        val runtime = File("src/main/java/com/ashraffarag/sentricam/device/android/DeviceRuntime.kt").readText()
        assertTrue(signalR.contains("while (isCurrent(runGeneration))"))
        assertTrue(signalR.contains("reportOperationalHealth"))
        assertTrue(uploads.contains("NetworkType.CONNECTED"))
        assertTrue(runtime.contains("OperationalSubsystem.UPLOAD"))
        assertTrue(runtime.contains("OperationalSubsystem.REALTIME"))
    }

    @Test
    fun `debug process death injection is shell authorized and absent from main manifest`() {
        val mainManifest = File("src/main/AndroidManifest.xml").readText()
        val debugManifest = File("src/debug/AndroidManifest.xml").readText()
        assertFalse(mainManifest.contains("RecoveryFaultInjectionReceiver"))
        assertTrue(debugManifest.contains("RecoveryFaultInjectionReceiver"))
        assertTrue(debugManifest.contains("android.permission.DUMP"))
    }
}
