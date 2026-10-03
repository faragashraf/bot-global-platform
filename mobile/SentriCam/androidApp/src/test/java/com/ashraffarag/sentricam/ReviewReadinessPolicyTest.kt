package com.ashraffarag.sentricam

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewReadinessPolicyTest {
    @Test
    fun finalized_recording_counts_once_only_when_a_segment_was_saved() {
        assertTrue(
            ReviewReadinessPolicy.shouldCountFinalizedRecording(
                sessionId = "session-1",
                successfulSegmentCount = 1,
                countedSessionIds = emptySet(),
            ),
        )
        assertFalse(
            ReviewReadinessPolicy.shouldCountFinalizedRecording(
                sessionId = "session-1",
                successfulSegmentCount = 1,
                countedSessionIds = setOf("session-1"),
            ),
        )
        assertFalse(
            ReviewReadinessPolicy.shouldCountFinalizedRecording(
                sessionId = "session-2",
                successfulSegmentCount = 0,
                countedSessionIds = emptySet(),
            ),
        )
    }

    @Test
    fun review_launch_defers_for_background_dialog_recording_and_configuration_states() {
        assertTrue(ready())
        assertFalse(ready(resumed = false))
        assertFalse(ready(audioPermissionDialogVisible = true))
        assertFalse(ready(audioPermissionDecisionInFlight = true))
        assertFalse(ready(motionConfigurationInProgress = true))
        assertFalse(ready(permissionRequestInFlight = true))
        assertFalse(ready(cameraStarting = true))
        assertFalse(ready(recordingEngineInitialized = false))
        assertFalse(ready(recordingControlsSettled = false))
    }

    private fun ready(
        resumed: Boolean = true,
        audioPermissionDialogVisible: Boolean = false,
        audioPermissionDecisionInFlight: Boolean = false,
        motionConfigurationInProgress: Boolean = false,
        permissionRequestInFlight: Boolean = false,
        cameraStarting: Boolean = false,
        recordingEngineInitialized: Boolean = true,
        recordingControlsSettled: Boolean = true,
    ) = ReviewReadinessPolicy.canLaunch(
        resumed = resumed,
        audioPermissionDialogVisible = audioPermissionDialogVisible,
        audioPermissionDecisionInFlight = audioPermissionDecisionInFlight,
        motionConfigurationInProgress = motionConfigurationInProgress,
        permissionRequestInFlight = permissionRequestInFlight,
        cameraStarting = cameraStarting,
        recordingEngineInitialized = recordingEngineInitialized,
        recordingControlsSettled = recordingControlsSettled,
    )
}
