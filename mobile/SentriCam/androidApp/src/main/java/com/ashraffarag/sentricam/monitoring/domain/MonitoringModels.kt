package com.ashraffarag.sentricam.monitoring.domain

data class MonitoringConfiguration(
    val motionDetectionEnabled: Boolean = false,
    val recordingOnMotionEnabled: Boolean = false,
    val audioEnabled: Boolean = false,
)

data class MonitoringPolicy(
    val stopWhenAppLeavesForeground: Boolean = false,
    val allowForegroundService: Boolean = true,
    val restartAfterUnexpectedStop: Boolean = false,
)

data class MonitoringSession(
    val sessionId: String,
    val startedAtMillis: Long,
    val configuration: MonitoringConfiguration,
    val policy: MonitoringPolicy,
)

enum class MonitoringStatus {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR,
    RESTARTING,
}

data class MonitoringState(
    val status: MonitoringStatus,
    val session: MonitoringSession? = null,
    val failureCode: String? = null,
) {
    companion object {
        fun stopped() = MonitoringState(MonitoringStatus.STOPPED)
    }
}

enum class MonitoringLifecycle {
    ACTIVITY_FOREGROUND,
    FOREGROUND_SERVICE,
}

sealed interface MonitoringTransitionResult {
    data object Accepted : MonitoringTransitionResult
    data object AlreadyApplied : MonitoringTransitionResult
    data class Rejected(val code: String) : MonitoringTransitionResult
}
