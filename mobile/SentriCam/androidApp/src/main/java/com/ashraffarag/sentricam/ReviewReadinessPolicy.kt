package com.ashraffarag.sentricam

object ReviewReadinessPolicy {
    fun canLaunch(
        resumed: Boolean,
        audioPermissionDialogVisible: Boolean,
        audioPermissionDecisionInFlight: Boolean,
        motionConfigurationInProgress: Boolean,
        permissionRequestInFlight: Boolean,
        cameraStarting: Boolean,
        recordingEngineInitialized: Boolean,
        recordingControlsSettled: Boolean,
    ): Boolean =
        resumed &&
            !audioPermissionDialogVisible &&
            !audioPermissionDecisionInFlight &&
            !motionConfigurationInProgress &&
            !permissionRequestInFlight &&
            !cameraStarting &&
            recordingEngineInitialized &&
            recordingControlsSettled

    fun shouldCountFinalizedRecording(
        sessionId: String,
        successfulSegmentCount: Int,
        countedSessionIds: Set<String>,
    ): Boolean =
        sessionId.isNotBlank() &&
            successfulSegmentCount > 0 &&
            sessionId !in countedSessionIds
}
