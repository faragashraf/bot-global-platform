package com.ashraffarag.sentricam.device.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DefaultDevice(
    initialState: DeviceState,
    private val mapper: DeviceStatusMapper,
) : Device {
    private val updateLock = Any()
    private val mutableState = MutableStateFlow(initialState)
    private val mutableSnapshot = MutableStateFlow(mapper.map(initialState))

    override val state: StateFlow<DeviceState> = mutableState.asStateFlow()
    override val snapshot: StateFlow<DeviceSnapshot> = mutableSnapshot.asStateFlow()

    fun update(transform: (DeviceState) -> DeviceState) {
        synchronized(updateLock) {
            val updated = transform(mutableState.value)
            mutableState.value = updated
            mutableSnapshot.value = mapper.map(updated)
        }
    }
}
