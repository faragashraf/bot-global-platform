package com.ashraffarag.sentricam.device.health

import com.ashraffarag.sentricam.device.domain.ActivityHistory
import com.ashraffarag.sentricam.device.domain.BatteryState
import com.ashraffarag.sentricam.device.domain.MemorySummary
import com.ashraffarag.sentricam.device.domain.StorageState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceHealthProvidersTest {
    @Test
    fun activityProviderExposesLastActivityMotionAndRecording() {
        val provider = MutableActivityHistoryProvider()

        provider.markActivity(10L)
        provider.markMotion(20L)
        provider.markRecording(30L)

        assertEquals(ActivityHistory(30L, 20L, 30L), provider.state.value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun activityProviderRejectsInvalidTimestamp() {
        MutableActivityHistoryProvider().markActivity(-1L)
    }

    @Test
    fun compositeProviderAggregatesObservableSourcesWithoutPolling() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val battery = FakeBattery(BatteryState(80, false, false))
        val storage = FakeStorage(StorageState(10L, 100L, true))
        val memory = FakeMemory(MemorySummary(30L, 200L, false))
        val activity = MutableActivityHistoryProvider(ActivityHistory(lastActivityAtMillis = 1L))
        val provider = DefaultDeviceHealthProvider(battery, storage, memory, activity, scope)

        assertEquals(80, provider.state.value.battery.levelPercent)
        assertTrue(provider.state.value.storage.isLow)
        battery.mutable.value = BatteryState(90, true, true)
        storage.mutable.value = StorageState(70L, 100L, false)

        assertEquals(90, provider.state.value.battery.levelPercent)
        assertTrue(provider.state.value.battery.isCharging == true)
        assertFalse(provider.state.value.storage.isLow)
        scope.cancel()
    }

    private class FakeBattery(initial: BatteryState) : BatteryStateProvider {
        val mutable = MutableStateFlow(initial)
        override val state = mutable
    }

    private class FakeStorage(initial: StorageState) : StorageStateProvider {
        val mutable = MutableStateFlow(initial)
        override val state = mutable
        override fun refresh() = Unit
    }

    private class FakeMemory(initial: MemorySummary) : MemorySummaryProvider {
        val mutable = MutableStateFlow(initial)
        override val state = mutable
        override fun refresh() = Unit
    }
}
