package com.ashraffarag.sentricam.cameracontrol.android

import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlValues
import com.ashraffarag.sentricam.device.command.DeviceCommandResult
import com.ashraffarag.sentricam.device.command.RecordingCommandPort
import com.ashraffarag.sentricam.device.domain.RecordingOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCommandCameraControlTest {
    @Test
    fun startAndStopUseRemoteOwnerAndPreserveStructuredResults() = runBlocking {
        val port = FakePort()
        val control = RecordingCommandCameraControl(port)

        val started = control.apply(CameraControlValues.START)
        port.stopResult = DeviceCommandResult.AlreadyApplied
        val stopped = control.apply(CameraControlValues.STOP)

        assertTrue(started.succeeded)
        assertEquals("recording_start_accepted", started.code)
        assertTrue(stopped.succeeded)
        assertEquals("recording_already_idle", stopped.code)
        assertEquals(listOf(RecordingOrigin.REMOTE, RecordingOrigin.REMOTE), port.origins)
    }

    @Test
    fun motionConflictIsExplicitAndNonTransient() = runBlocking {
        val port = FakePort().apply {
            startResult = DeviceCommandResult.Rejected("recording_owned_by_motion")
        }

        val result = RecordingCommandCameraControl(port).apply(CameraControlValues.START)

        assertFalse(result.succeeded)
        assertFalse(result.transientFailure)
        assertEquals("recording_owned_by_motion", result.code)
    }

    private class FakePort : RecordingCommandPort {
        val origins = mutableListOf<RecordingOrigin>()
        var startResult: DeviceCommandResult = DeviceCommandResult.Accepted
        var stopResult: DeviceCommandResult = DeviceCommandResult.Accepted
        override suspend fun startRecording(origin: RecordingOrigin): DeviceCommandResult {
            origins += origin
            return startResult
        }
        override suspend fun stopRecording(origin: RecordingOrigin): DeviceCommandResult {
            origins += origin
            return stopResult
        }
    }
}
