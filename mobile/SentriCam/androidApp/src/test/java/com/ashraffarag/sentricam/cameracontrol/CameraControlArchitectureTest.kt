package com.ashraffarag.sentricam.cameracontrol

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraControlArchitectureTest {
    @Test
    fun signalRTransportsOneUnifiedEnvelopeAndContainsNoCameraBusinessLogic() {
        val client = source("src/main/java/com/ashraffarag/sentricam/communication/signalr/MicrosoftSignalRClient.kt")
        val service = source("src/main/java/com/ashraffarag/sentricam/cameracontrol/capability/CameraControlService.kt")

        assertTrue(client.contains("ReceiveCameraControlCommand"))
        assertTrue(client.contains("CameraControlCommandEnvelope::class.java"))
        assertFalse(client.contains("setZoomRatio"))
        assertFalse(client.contains("enableTorch"))
        assertTrue(service.contains("Channel<CameraControlCommandEnvelope>(Channel.UNLIMITED)"))
    }

    @Test
    fun persistentSettingsCoverEveryWritableControlAndNoSecondCameraPipelineExists() {
        val repository = source("src/main/java/com/ashraffarag/sentricam/cameracontrol/android/SharedPreferencesCameraControlSettingsRepository.kt")
        val hardware = source("src/main/java/com/ashraffarag/sentricam/cameracontrol/android/AndroidCameraXControl.kt")

        listOf("lens", "zoom", "torch", "exposureCompensation", "preview", "framesPerSecond", "resolution", "bitrate", "quality", "nightProfile").forEach {
            assertTrue("Missing persisted setting $it", repository.contains(it))
        }
        assertFalse(hardware.contains("bindToLifecycle"))
        assertFalse(hardware.contains("ProcessCameraProvider"))
    }

    private fun source(relativePath: String): String = File(relativePath).readText()
}
