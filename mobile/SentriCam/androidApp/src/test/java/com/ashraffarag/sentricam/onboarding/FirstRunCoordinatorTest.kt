package com.ashraffarag.sentricam.onboarding

import com.ashraffarag.sentricam.monitoring.domain.MonitoringSettings
import com.ashraffarag.sentricam.monitoring.domain.MonitoringSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunCoordinatorTest {
    private val stateStore = MemoryStateStore()
    private val monitoring = MemoryMonitoringSettings()
    private val coordinator = FirstRunCoordinator(stateStore, monitoring)

    @Test
    fun freshPairingPersistsAutomaticMonitoringWithoutEnablingBeforePairing() {
        val launched = coordinator.detectLaunch(existingRegistration = false)
        assertTrue(launched.firstLaunchDetected)
        assertFalse(monitoring.value.enabled)

        val paired = coordinator.pairingSucceeded(cameraPermissionGranted = true)

        assertTrue(paired.firstPairingCompleted)
        assertTrue(paired.monitoringAutoEnabled)
        assertTrue(monitoring.value.enabled)
        assertTrue(monitoring.value.autoStart)
        assertTrue(monitoring.value.restartOnFailure)
    }

    @Test
    fun missingAndroidPermissionProducesExplicitActionRequiredState() {
        val result = coordinator.pairingSucceeded(cameraPermissionGranted = false)

        assertEquals(FirstRunCoordinator.ACTION_CAMERA_PERMISSION, result.actionRequiredCode)
    }

    @Test
    fun existingRegistrationIsMigrationNotASecondAutomaticPairing() {
        monitoring.value = MonitoringSettings(enabled = false)

        val result = coordinator.detectLaunch(existingRegistration = true)

        assertTrue(result.firstPairingCompleted)
        assertFalse(monitoring.value.enabled)
    }

    private class MemoryStateStore : FirstRunStateStore {
        var value = FirstRunState()
        override fun load() = value
        override fun save(state: FirstRunState) { value = state }
    }

    private class MemoryMonitoringSettings : MonitoringSettingsRepository {
        var value = MonitoringSettings()
        override fun load() = value
        override fun save(settings: MonitoringSettings) { value = settings }
    }
}
