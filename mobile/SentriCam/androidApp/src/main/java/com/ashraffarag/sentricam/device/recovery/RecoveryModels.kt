package com.ashraffarag.sentricam.device.recovery

enum class OperationalSubsystem {
    REALTIME,
    LIVE,
    RECORDING,
    UPLOAD,
    MOTION,
    CAMERA,
    BATTERY,
    STORAGE,
    COMMAND_QUEUE,
}

enum class OperationalLifecycleState {
    IDLE,
    STARTING,
    RUNNING,
    RECOVERING,
    DEGRADED,
    OFFLINE,
    FAILED,
    ACTION_REQUIRED,
}

enum class OperationalHealthState {
    HEALTHY,
    RECOVERING,
    DEGRADED,
    OFFLINE,
    ACTION_REQUIRED,
    UNKNOWN,
}

data class SubsystemHealthReport(
    val subsystem: OperationalSubsystem,
    val lifecycle: OperationalLifecycleState = OperationalLifecycleState.IDLE,
    val health: OperationalHealthState = OperationalHealthState.UNKNOWN,
    val recoveryReason: String? = null,
    val reconnectCount: Int = 0,
    val lastFailureAtMillis: Long? = null,
    val lastRecoveryAtMillis: Long? = null,
    val recoveryDurationMillis: Long? = null,
    val updatedAtMillis: Long = 0L,
)

data class OperationalHealthSnapshot(
    val overall: OperationalHealthState,
    val realtime: SubsystemHealthReport,
    val live: SubsystemHealthReport,
    val recording: SubsystemHealthReport,
    val upload: SubsystemHealthReport,
    val motion: SubsystemHealthReport,
    val camera: SubsystemHealthReport,
    val battery: SubsystemHealthReport,
    val storage: SubsystemHealthReport,
    val commandQueue: SubsystemHealthReport,
    val updatedAtMillis: Long,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    fun reports(): List<SubsystemHealthReport> = listOf(
        realtime,
        live,
        recording,
        upload,
        motion,
        camera,
        battery,
        storage,
        commandQueue,
    )

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        fun initial(nowMillis: Long = 0L): OperationalHealthSnapshot {
            fun report(subsystem: OperationalSubsystem) = SubsystemHealthReport(
                subsystem = subsystem,
                updatedAtMillis = nowMillis,
            )
            return OperationalHealthSnapshot(
                overall = OperationalHealthState.UNKNOWN,
                realtime = report(OperationalSubsystem.REALTIME),
                live = report(OperationalSubsystem.LIVE),
                recording = report(OperationalSubsystem.RECORDING),
                upload = report(OperationalSubsystem.UPLOAD),
                motion = report(OperationalSubsystem.MOTION),
                camera = report(OperationalSubsystem.CAMERA),
                battery = report(OperationalSubsystem.BATTERY),
                storage = report(OperationalSubsystem.STORAGE),
                commandQueue = report(OperationalSubsystem.COMMAND_QUEUE),
                updatedAtMillis = nowMillis,
            )
        }
    }
}

data class RecoveryPolicy(
    val retryIndefinitely: Boolean,
    val baseDelayMillis: Long,
    val maximumDelayMillis: Long,
    val actionRequiredReasons: Set<String> = emptySet(),
) {
    init {
        require(baseDelayMillis >= 0L)
        require(maximumDelayMillis >= baseDelayMillis)
    }

    fun delayForAttempt(attempt: Int): Long {
        if (attempt <= 0 || baseDelayMillis == 0L) return 0L
        var delay = baseDelayMillis
        repeat((attempt - 1).coerceAtMost(30)) {
            delay = (delay * 2L).coerceAtMost(maximumDelayMillis)
        }
        return delay
    }
}

object DeviceRecoveryPolicies {
    val realtime = RecoveryPolicy(true, 1_000L, 30_000L, setOf("authentication_failed", "credentials_missing"))
    val live = RecoveryPolicy(true, 1_000L, 10_000L)
    val recording = RecoveryPolicy(true, 1_000L, 30_000L, setOf("permission_missing", "insufficient_storage"))
    val upload = RecoveryPolicy(true, 15_000L, 5 * 60_000L, setOf("file_unavailable"))
    val motion = RecoveryPolicy(true, 1_000L, 30_000L)
    val camera = RecoveryPolicy(true, 1_000L, 30_000L, setOf("camera_permission_missing"))
    val commandQueue = RecoveryPolicy(true, 1_000L, 10_000L)
}

object DeviceRecoveryReasons {
    const val FOREGROUND_CAMERA_ACTION_REQUIRED = "foreground_camera_action_required"
}

object AndroidRecoveryPolicy {
    // Android 11+ does not grant while-in-use camera access to a foreground service started from
    // BOOT_COMPLETED. Realtime can recover, but camera ownership must wait for the app foreground.
    fun requiresForegroundCameraActionAfterBoot(sdkInt: Int): Boolean = sdkInt >= 30
}

internal fun deriveOverallHealth(reports: Iterable<SubsystemHealthReport>): OperationalHealthState {
    val states = reports.map(SubsystemHealthReport::health).toSet()
    return when {
        OperationalHealthState.ACTION_REQUIRED in states -> OperationalHealthState.ACTION_REQUIRED
        OperationalHealthState.OFFLINE in states -> OperationalHealthState.OFFLINE
        OperationalHealthState.DEGRADED in states -> OperationalHealthState.DEGRADED
        OperationalHealthState.RECOVERING in states -> OperationalHealthState.RECOVERING
        states.isNotEmpty() && states.all { it == OperationalHealthState.HEALTHY } -> OperationalHealthState.HEALTHY
        else -> OperationalHealthState.UNKNOWN
    }
}
