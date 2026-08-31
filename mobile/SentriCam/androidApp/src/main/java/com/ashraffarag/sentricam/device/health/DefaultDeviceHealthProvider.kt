package com.ashraffarag.sentricam.device.health

import com.ashraffarag.sentricam.device.domain.ActivityHistory
import com.ashraffarag.sentricam.device.domain.DeviceHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class DefaultDeviceHealthProvider(
    battery: BatteryStateProvider,
    storage: StorageStateProvider,
    memory: MemorySummaryProvider,
    activity: ActivityHistoryProvider,
    scope: CoroutineScope,
) : DeviceHealthProvider {
    override val state: StateFlow<DeviceHealth> = combine(
        battery.state,
        storage.state,
        memory.state,
        activity.state,
    ) { batteryState, storageState, memoryState, activityState ->
        DeviceHealth(batteryState, storageState, memoryState, activityState)
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        DeviceHealth(
            battery.state.value,
            storage.state.value,
            memory.state.value,
            activity.state.value,
        ),
    )
}
class MutableActivityHistoryProvider(
    initial: ActivityHistory = ActivityHistory(),
) : ActivityHistoryProvider {
    private val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<ActivityHistory> = mutableState

    override fun markActivity(timestampMillis: Long) = update(timestampMillis) {
        copy(lastActivityAtMillis = timestampMillis)
    }

    override fun markMotion(timestampMillis: Long) = update(timestampMillis) {
        copy(lastMotionAtMillis = timestampMillis, lastActivityAtMillis = timestampMillis)
    }

    override fun markRecording(timestampMillis: Long) = update(timestampMillis) {
        copy(lastRecordingAtMillis = timestampMillis, lastActivityAtMillis = timestampMillis)
    }

    private inline fun update(timestampMillis: Long, transform: ActivityHistory.() -> ActivityHistory) {
        require(timestampMillis >= 0L) { "Timestamp cannot be negative" }
        mutableState.value = mutableState.value.transform()
    }
}
