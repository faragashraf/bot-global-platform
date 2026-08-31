package com.ashraffarag.sentricam.camera.presentation

import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCameraControlPolicyTest {
    @Test
    fun offAndOnMapToTheExistingTorchSetting() {
        val current = CameraControlSettings(torch = false)

        val enabled = requireNotNull(
            LiveCameraControlPolicy.settingsForFlashMode(current, LiveFlashMode.ON),
        )
        val disabled = requireNotNull(
            LiveCameraControlPolicy.settingsForFlashMode(enabled, LiveFlashMode.OFF),
        )

        assertTrue(enabled.torch)
        assertFalse(disabled.torch)
        assertEquals(LiveFlashMode.ON, LiveCameraControlPolicy.flashMode(enabled))
        assertEquals(LiveFlashMode.OFF, LiveCameraControlPolicy.flashMode(disabled))
    }

    @Test
    fun autoRemainsUnavailableInsteadOfFakingATorchMode() {
        assertFalse(LiveCameraControlPolicy.AUTO_FLASH_AVAILABLE)
        assertNull(
            LiveCameraControlPolicy.settingsForFlashMode(
                CameraControlSettings(),
                LiveFlashMode.AUTO,
            ),
        )
    }

    @Test
    fun zoomIsClampedToTheReportedCameraCapability() {
        assertEquals(1.0, LiveCameraControlPolicy.normalizedZoom(0.2f, 1.0, 8.0), 0.0)
        assertEquals(3.5, LiveCameraControlPolicy.normalizedZoom(3.5f, 1.0, 8.0), 0.0)
        assertEquals(8.0, LiveCameraControlPolicy.normalizedZoom(12f, 1.0, 8.0), 0.0)
    }
}
