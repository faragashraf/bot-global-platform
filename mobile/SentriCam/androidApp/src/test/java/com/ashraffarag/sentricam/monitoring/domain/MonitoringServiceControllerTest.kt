package com.ashraffarag.sentricam.monitoring.domain

import com.ashraffarag.sentricam.device.domain.DeviceClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonitoringServiceControllerTest {
    @Test
    fun startStopAndRestartFollowLegalLifecycleTransitions() = runBlocking {
        var now = 10L
        var id = 0
        val controller = DefaultMonitoringServiceController(
            clock = DeviceClock { now },
            sessionIdFactory = { "session-${++id}" },
        )
        val configuration = MonitoringConfiguration(motionDetectionEnabled = true)

        assertEquals(MonitoringTransitionResult.Accepted, controller.start(configuration))
        assertEquals(MonitoringStatus.RUNNING, controller.state.value.status)
        assertEquals(configuration, controller.state.value.session?.configuration)
        val firstSession = controller.state.value.session?.sessionId
        assertEquals(MonitoringTransitionResult.AlreadyApplied, controller.start())

        now = 20L
        assertEquals(MonitoringTransitionResult.Accepted, controller.restart())
        assertEquals(MonitoringStatus.RUNNING, controller.state.value.status)
        assertNotEquals(firstSession, controller.state.value.session?.sessionId)
        assertEquals(configuration, controller.state.value.session?.configuration)

        assertEquals(MonitoringTransitionResult.Accepted, controller.stop())
        assertEquals(MonitoringStatus.STOPPED, controller.state.value.status)
        assertNull(controller.state.value.session)
        assertEquals(MonitoringTransitionResult.AlreadyApplied, controller.stop())
    }

    @Test
    fun domainFallbackDoesNotPretendToBeTheAndroidServiceAdapter() {
        val controller = DefaultMonitoringServiceController(DeviceClock { 1L })
        assertEquals(MonitoringLifecycle.ACTIVITY_FOREGROUND, controller.lifecycle)
    }

    @Test
    fun foregroundRuntimeContractExposesEveryRestartSafeServiceState() {
        assertEquals(
            listOf("STOPPED", "STARTING", "RUNNING", "STOPPING", "ERROR", "RESTARTING"),
            MonitoringStatus.entries.map(MonitoringStatus::name),
        )
        assertEquals("FOREGROUND_SERVICE", MonitoringLifecycle.FOREGROUND_SERVICE.name)
    }
}
