package com.ashraffarag.sentricam.camera.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLensSwitchStateTest {
    private val bothLenses = CameraLensFacing.entries.toSet()

    @Test
    fun lensTransitionsFromRearToFrontAndBack() {
        val rear = CameraLensSwitchState(CameraLensFacing.REAR, bothLenses)
        val front = rear.beginSwitch(cameraReady = true, recordingInProgress = false).completeSwitch()
        val rearAgain = front.beginSwitch(cameraReady = true, recordingInProgress = false).completeSwitch()

        assertEquals(CameraLensFacing.FRONT, front.selectedLens)
        assertEquals(CameraLensFacing.REAR, rearAgain.selectedLens)
    }

    @Test
    fun switchIsUnavailableDuringRecording() {
        val state = CameraLensSwitchState(CameraLensFacing.REAR, bothLenses)

        val result = state.beginSwitch(cameraReady = true, recordingInProgress = true)

        assertEquals(state, result)
        assertFalse(result.switchInProgress)
    }

    @Test
    fun singleCameraDeviceHidesSwitchControl() {
        val state = CameraLensSwitchState(
            selectedLens = CameraLensFacing.REAR,
            availableLenses = setOf(CameraLensFacing.REAR),
        )

        assertFalse(state.showSwitchControl)
        assertFalse(state.canSwitch(cameraReady = true, recordingInProgress = false))
    }

    @Test
    fun repeatedPressCannotStartParallelSwitch() {
        val controller = CameraLensSwitchController(CameraLensFacing.REAR).apply {
            updateAvailableLenses(bothLenses)
        }
        val firstRequest = controller.requestSwitch(cameraReady = true, recordingInProgress = false)
        val repeatedRequest = controller.requestSwitch(cameraReady = true, recordingInProgress = false)

        assertEquals(CameraLensFacing.FRONT, firstRequest)
        assertNull(repeatedRequest)
        assertTrue(controller.state.switchInProgress)
        assertEquals(CameraLensFacing.FRONT, controller.state.pendingLens)
    }

    @Test
    fun cancelledSwitchKeepsOriginalLens() {
        val cancelled = CameraLensSwitchState(CameraLensFacing.REAR, bothLenses)
            .beginSwitch(cameraReady = true, recordingInProgress = false)
            .cancelSwitch()

        assertEquals(CameraLensFacing.REAR, cancelled.selectedLens)
        assertNull(cancelled.pendingLens)
    }
}
