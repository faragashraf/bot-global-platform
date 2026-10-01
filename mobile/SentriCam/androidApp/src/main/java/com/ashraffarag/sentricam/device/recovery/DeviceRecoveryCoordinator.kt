package com.ashraffarag.sentricam.device.recovery

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

fun interface RecoveryClock {
    fun nowMillis(): Long
}

interface RecoveryStateStore {
    fun load(): OperationalHealthSnapshot?
    fun save(snapshot: OperationalHealthSnapshot)
}

class SharedPreferencesRecoveryStateStore(
    context: Context,
    private val gson: Gson = Gson(),
) : RecoveryStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun load(): OperationalHealthSnapshot? = preferences.getString(KEY_SNAPSHOT, null)?.let { json ->
        runCatching { gson.fromJson(json, OperationalHealthSnapshot::class.java) }.getOrNull()
    }

    override fun save(snapshot: OperationalHealthSnapshot) {
        check(preferences.edit().putString(KEY_SNAPSHOT, gson.toJson(snapshot)).commit()) {
            "Recovery health persistence failed"
        }
    }

    private companion object {
        const val PREFERENCES = "device_recovery_health"
        const val KEY_SNAPSHOT = "snapshot"
    }
}

class DeviceRecoveryCoordinator(
    private val clock: RecoveryClock,
    private val store: RecoveryStateStore,
) {
    private val mutableState = MutableStateFlow(store.load() ?: OperationalHealthSnapshot.initial(clock.nowMillis()))
    val state: StateFlow<OperationalHealthSnapshot> = mutableState.asStateFlow()

    @Synchronized
    fun update(
        subsystem: OperationalSubsystem,
        lifecycle: OperationalLifecycleState,
        health: OperationalHealthState,
        reason: String? = null,
        reconnectCount: Int? = null,
    ): OperationalHealthSnapshot {
        val now = clock.nowMillis()
        val currentSnapshot = mutableState.value
        val previous = currentSnapshot.report(subsystem)
        val nextReason = reason ?: if (health == OperationalHealthState.HEALTHY) null else previous.recoveryReason
        val nextReconnectCount = reconnectCount ?: previous.reconnectCount
        if (
            previous.lifecycle == lifecycle &&
            previous.health == health &&
            previous.recoveryReason == nextReason &&
            previous.reconnectCount == nextReconnectCount
        ) {
            return currentSnapshot
        }
        val failureStarted = health in FAILURE_STATES && previous.health !in FAILURE_STATES
        val recovered = health == OperationalHealthState.HEALTHY && previous.health in FAILURE_STATES
        val failureAt = when {
            failureStarted -> now
            health in FAILURE_STATES -> previous.lastFailureAtMillis ?: now
            else -> previous.lastFailureAtMillis
        }
        val next = previous.copy(
            lifecycle = lifecycle,
            health = health,
            recoveryReason = nextReason,
            reconnectCount = nextReconnectCount,
            lastFailureAtMillis = failureAt,
            lastRecoveryAtMillis = if (recovered) now else previous.lastRecoveryAtMillis,
            recoveryDurationMillis = if (recovered && failureAt != null) now - failureAt else previous.recoveryDurationMillis,
            updatedAtMillis = now,
        )
        val replaced = currentSnapshot.replace(next, now)
        mutableState.value = replaced
        store.save(replaced)
        return replaced
    }

    private companion object {
        val FAILURE_STATES = setOf(
            OperationalHealthState.RECOVERING,
            OperationalHealthState.DEGRADED,
            OperationalHealthState.OFFLINE,
            OperationalHealthState.ACTION_REQUIRED,
        )
    }
}

private fun OperationalHealthSnapshot.report(subsystem: OperationalSubsystem): SubsystemHealthReport = when (subsystem) {
    OperationalSubsystem.REALTIME -> realtime
    OperationalSubsystem.LIVE -> live
    OperationalSubsystem.RECORDING -> recording
    OperationalSubsystem.UPLOAD -> upload
    OperationalSubsystem.MOTION -> motion
    OperationalSubsystem.CAMERA -> camera
    OperationalSubsystem.BATTERY -> battery
    OperationalSubsystem.STORAGE -> storage
    OperationalSubsystem.COMMAND_QUEUE -> commandQueue
}

private fun OperationalHealthSnapshot.replace(
    report: SubsystemHealthReport,
    nowMillis: Long,
): OperationalHealthSnapshot {
    val provisional = when (report.subsystem) {
        OperationalSubsystem.REALTIME -> copy(realtime = report, updatedAtMillis = nowMillis)
        OperationalSubsystem.LIVE -> copy(live = report, updatedAtMillis = nowMillis)
        OperationalSubsystem.RECORDING -> copy(recording = report, updatedAtMillis = nowMillis)
        OperationalSubsystem.UPLOAD -> copy(upload = report, updatedAtMillis = nowMillis)
        OperationalSubsystem.MOTION -> copy(motion = report, updatedAtMillis = nowMillis)
        OperationalSubsystem.CAMERA -> copy(camera = report, updatedAtMillis = nowMillis)
        OperationalSubsystem.BATTERY -> copy(battery = report, updatedAtMillis = nowMillis)
        OperationalSubsystem.STORAGE -> copy(storage = report, updatedAtMillis = nowMillis)
        OperationalSubsystem.COMMAND_QUEUE -> copy(commandQueue = report, updatedAtMillis = nowMillis)
    }
    return provisional.copy(overall = deriveOverallHealth(provisional.reports()))
}
