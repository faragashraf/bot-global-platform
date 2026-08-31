package com.ashraffarag.sentricam.device.health

import com.ashraffarag.sentricam.device.domain.ActivityHistory
import com.ashraffarag.sentricam.device.domain.BatteryState
import com.ashraffarag.sentricam.device.domain.DeviceHealth
import com.ashraffarag.sentricam.device.domain.MemorySummary
import com.ashraffarag.sentricam.device.domain.StorageState
import kotlinx.coroutines.flow.StateFlow

interface BatteryStateProvider {
    val state: StateFlow<BatteryState>
}
interface StorageStateProvider {
    val state: StateFlow<StorageState>
    fun refresh()
}

interface MemorySummaryProvider {
    val state: StateFlow<MemorySummary>
    fun refresh()
}

interface ActivityHistoryProvider {
    val state: StateFlow<ActivityHistory>
    fun markActivity(timestampMillis: Long)
    fun markMotion(timestampMillis: Long)
    fun markRecording(timestampMillis: Long)
}

interface DeviceHealthProvider {
    val state: StateFlow<DeviceHealth>
}
