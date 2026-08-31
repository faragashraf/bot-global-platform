package com.ashraffarag.sentricam.device.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RecoveryFaultInjectionTest {
    @Test
    fun `transient fault matrix always reaches a terminal observable recovery`() {
        var now = 1_000L
        val store = MemoryStore()
        val coordinator = DeviceRecoveryCoordinator(RecoveryClock { now }, store)
        val faults = listOf(
            OperationalSubsystem.REALTIME to "hub_restart",
            OperationalSubsystem.REALTIME to "wifi_loss",
            OperationalSubsystem.REALTIME to "wifi_change",
            OperationalSubsystem.CAMERA to "camera_busy",
            OperationalSubsystem.UPLOAD to "upload_interrupted",
            OperationalSubsystem.MOTION to "analyzer_unavailable",
            OperationalSubsystem.RECORDING to "recording_interrupted",
            OperationalSubsystem.COMMAND_QUEUE to "device_offline",
        )

        faults.forEachIndexed { index, (subsystem, reason) ->
            coordinator.update(
                subsystem,
                OperationalLifecycleState.RECOVERING,
                OperationalHealthState.RECOVERING,
                reason,
                reconnectCount = index + 1,
            )
            now += 2_000L
            val recovered = coordinator.update(
                subsystem,
                OperationalLifecycleState.RUNNING,
                OperationalHealthState.HEALTHY,
                reconnectCount = index + 1,
            ).reports().single { it.subsystem == subsystem }

            assertEquals(OperationalHealthState.HEALTHY, recovered.health)
            assertNotNull(recovered.lastRecoveryAtMillis)
            assertNotNull(recovered.recoveryDurationMillis)
        }
    }

    @Test
    fun `process death rehydrates failure and recovery evidence`() {
        var now = 2_000L
        val store = MemoryStore()
        DeviceRecoveryCoordinator(RecoveryClock { now }, store).update(
            OperationalSubsystem.REALTIME,
            OperationalLifecycleState.RECOVERING,
            OperationalHealthState.RECOVERING,
            "process_restart",
            reconnectCount = 1,
        )

        now = 5_000L
        val restored = DeviceRecoveryCoordinator(RecoveryClock { now }, store)
        val recovered = restored.update(
            OperationalSubsystem.REALTIME,
            OperationalLifecycleState.RUNNING,
            OperationalHealthState.HEALTHY,
            reconnectCount = 1,
        ).realtime

        assertEquals(2_000L, recovered.lastFailureAtMillis)
        assertEquals(3_000L, recovered.recoveryDurationMillis)
    }

    @Test
    fun `storage full becomes explicit action required`() {
        val coordinator = DeviceRecoveryCoordinator(RecoveryClock { 1_000L }, MemoryStore())
        val storage = coordinator.update(
            OperationalSubsystem.STORAGE,
            OperationalLifecycleState.ACTION_REQUIRED,
            OperationalHealthState.ACTION_REQUIRED,
            "storage_full",
        ).storage

        assertEquals(OperationalLifecycleState.ACTION_REQUIRED, storage.lifecycle)
        assertEquals("storage_full", storage.recoveryReason)
    }

    private class MemoryStore : RecoveryStateStore {
        var value: OperationalHealthSnapshot? = null
        override fun load() = value
        override fun save(snapshot: OperationalHealthSnapshot) { value = snapshot }
    }
}
