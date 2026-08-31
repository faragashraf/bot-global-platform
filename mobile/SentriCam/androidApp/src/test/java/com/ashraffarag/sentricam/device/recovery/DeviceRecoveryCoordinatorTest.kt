package com.ashraffarag.sentricam.device.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceRecoveryCoordinatorTest {
    @Test
    fun `failure and recovery preserve observability`() {
        var now = 1_000L
        val store = MemoryStore()
        val coordinator = DeviceRecoveryCoordinator(RecoveryClock { now }, store)

        coordinator.update(
            OperationalSubsystem.REALTIME,
            OperationalLifecycleState.RECOVERING,
            OperationalHealthState.RECOVERING,
            "network_offline",
            reconnectCount = 3,
        )
        now = 4_500L
        val recovered = coordinator.update(
            OperationalSubsystem.REALTIME,
            OperationalLifecycleState.RUNNING,
            OperationalHealthState.HEALTHY,
            reconnectCount = 3,
        ).realtime

        assertEquals(1_000L, recovered.lastFailureAtMillis)
        assertEquals(4_500L, recovered.lastRecoveryAtMillis)
        assertEquals(3_500L, recovered.recoveryDurationMillis)
        assertEquals(3, recovered.reconnectCount)
        assertNull(recovered.recoveryReason)
        assertEquals(recovered, store.value?.realtime)
    }

    @Test
    fun `overall health reports action required before degraded states`() {
        val coordinator = DeviceRecoveryCoordinator(RecoveryClock { 10L }, MemoryStore())
        coordinator.update(
            OperationalSubsystem.STORAGE,
            OperationalLifecycleState.DEGRADED,
            OperationalHealthState.DEGRADED,
            "storage_low",
        )
        val state = coordinator.update(
            OperationalSubsystem.CAMERA,
            OperationalLifecycleState.ACTION_REQUIRED,
            OperationalHealthState.ACTION_REQUIRED,
            "camera_permission_missing",
        )
        assertEquals(OperationalHealthState.ACTION_REQUIRED, state.overall)
    }

    @Test
    fun `recovery policies use bounded exponential backoff`() {
        val policy = RecoveryPolicy(true, 1_000L, 8_000L)
        assertEquals(0L, policy.delayForAttempt(0))
        assertEquals(1_000L, policy.delayForAttempt(1))
        assertEquals(2_000L, policy.delayForAttempt(2))
        assertEquals(8_000L, policy.delayForAttempt(10))
    }

    @Test
    fun `unchanged health does not turn heartbeat into a health event`() {
        var now = 1_000L
        val store = MemoryStore()
        val coordinator = DeviceRecoveryCoordinator(RecoveryClock { now }, store)
        val initial = coordinator.update(
            OperationalSubsystem.REALTIME,
            OperationalLifecycleState.RUNNING,
            OperationalHealthState.HEALTHY,
            reconnectCount = 0,
        )

        now = 16_000L
        val afterHeartbeat = coordinator.update(
            OperationalSubsystem.REALTIME,
            OperationalLifecycleState.RUNNING,
            OperationalHealthState.HEALTHY,
            reconnectCount = 0,
        )

        assertEquals(initial, afterHeartbeat)
        assertEquals(1, store.saveCount)
    }

    @Test
    fun `boot recovery defers foreground camera ownership on modern Android`() {
        assertEquals(false, AndroidRecoveryPolicy.requiresForegroundCameraActionAfterBoot(29))
        assertEquals(true, AndroidRecoveryPolicy.requiresForegroundCameraActionAfterBoot(30))
        assertEquals(true, AndroidRecoveryPolicy.requiresForegroundCameraActionAfterBoot(36))
    }

    private class MemoryStore : RecoveryStateStore {
        var value: OperationalHealthSnapshot? = null
        var saveCount = 0
        override fun load() = value
        override fun save(snapshot: OperationalHealthSnapshot) {
            value = snapshot
            saveCount += 1
        }
    }
}
