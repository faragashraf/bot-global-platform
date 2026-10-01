package com.ashraffarag.sentricam.settings.domain

import com.ashraffarag.sentricam.motion.domain.MotionSensitivity
import com.ashraffarag.sentricam.monitoring.domain.MonitoringSettings
import com.ashraffarag.sentricam.settings.capability.AppSettingsCoordinator
import com.ashraffarag.sentricam.settings.capability.MotionConfigApplyMode
import com.ashraffarag.sentricam.settings.capability.MotionLiveConfigPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsArchitectureTest {
    @Test
    fun sectionsHaveStableProductOrder() {
        assertEquals(
            listOf(
                SettingsSection.CAMERA,
                SettingsSection.RECORDING,
                SettingsSection.MOTION_DETECTION,
                SettingsSection.MONITORING,
                SettingsSection.SMART_DETECTION,
                SettingsSection.DEVICE,
                SettingsSection.SYSTEM_CONNECTIVITY,
            ),
            SettingsSection.entries,
        )
    }

    @Test
    fun entryPointsResolveToTheirRequestedSections() {
        assertEquals(SettingsSection.CAMERA, SettingsDestination.general().section)
        assertEquals(SettingsSection.RECORDING, SettingsDestination.recording().section)
        assertEquals(SettingsSection.MOTION_DETECTION, SettingsDestination.motion().section)
        assertEquals(SettingsSection.MONITORING, SettingsDestination.monitoring().section)
        assertEquals(SettingsSection.SYSTEM_CONNECTIVITY, SettingsDestination.connectivity().section)
    }

    @Test
    fun navigationSelectionAndDraftSurviveCoordinatorReuse() {
        val repository = FakeSettingsRepository()
        val coordinator = AppSettingsCoordinator(repository, SettingsDestination.general())
        coordinator.select(SettingsSection.MOTION_DETECTION)
        coordinator.updateMotion(
            coordinator.state.value.draft.motion.copy(sensitivity = MotionSensitivity.HIGH),
        )

        assertEquals(SettingsSection.MOTION_DETECTION, coordinator.state.value.navigation.selectedSection)
        assertEquals(MotionSensitivity.HIGH, coordinator.state.value.draft.motion.sensitivity)
        assertTrue(coordinator.state.value.hasUnsavedChanges)

        coordinator.apply()
        assertFalse(coordinator.state.value.hasUnsavedChanges)
        assertEquals(MotionSensitivity.HIGH, repository.saved.motion.sensitivity)
    }

    @Test
    fun monitoringDraftPersistsThroughTheSingleSettingsOwner() {
        val repository = FakeSettingsRepository()
        val coordinator = AppSettingsCoordinator(repository, SettingsDestination.monitoring())

        coordinator.updateMonitoring(MonitoringSettings(enabled = true, autoStart = true))
        coordinator.apply()

        assertTrue(repository.saved.monitoring.enabled)
        assertTrue(repository.saved.monitoring.autoStart)
        assertFalse(coordinator.state.value.hasUnsavedChanges)
    }

    @Test
    fun responsiveNavigationSelectsCompactTwoPaneAndRail() {
        assertEquals(
            SettingsNavigationMode.COMPACT_TABS,
            SettingsResponsivePolicy.navigationMode(widthDp = 360, heightDp = 720),
        )
        assertEquals(
            SettingsNavigationMode.TWO_PANE,
            SettingsResponsivePolicy.navigationMode(widthDp = 590, heightDp = 360),
        )
        assertEquals(
            SettingsNavigationMode.PERSISTENT_RAIL,
            SettingsResponsivePolicy.navigationMode(widthDp = 800, heightDp = 1280),
        )
        assertEquals(
            SettingsNavigationMode.PERSISTENT_RAIL,
            SettingsResponsivePolicy.navigationMode(widthDp = 1280, heightDp = 800),
        )
    }

    @Test
    fun rtlDoesNotChangeSemanticSectionOrFocusOrder() {
        val semanticOrder = SettingsSection.entries.map(SettingsSection::name)
        assertEquals("CAMERA", semanticOrder.first())
        assertEquals("SYSTEM_CONNECTIVITY", semanticOrder.last())
        assertEquals(semanticOrder.distinct(), semanticOrder)
    }

    @Test
    fun liveMotionChangesDoNotRequestCameraRebind() {
        val old = AppSettingsSnapshot().motion.copy(enabled = true)
        assertEquals(
            MotionConfigApplyMode.LIVE_UPDATE,
            MotionLiveConfigPolicy.mode(old, old.copy(sensitivity = MotionSensitivity.ADVANCED)),
        )
        assertEquals(
            MotionConfigApplyMode.LIVE_UPDATE,
            MotionLiveConfigPolicy.mode(old, old.copy(enabled = false)),
        )
        assertEquals(MotionConfigApplyMode.NONE, MotionLiveConfigPolicy.mode(old, old))
    }

    @Test
    fun remoteMotionChangeRefreshesAndroidScreenButNeverOverwritesAnUnsavedLocalDraft() {
        val repository = FakeSettingsRepository()
        val coordinator = AppSettingsCoordinator(repository, SettingsDestination.motion())
        val remote = coordinator.state.value.applied.motion.copy(sensitivity = MotionSensitivity.HIGH)

        assertTrue(coordinator.refreshMotion(remote))
        assertEquals(MotionSensitivity.HIGH, coordinator.state.value.applied.motion.sensitivity)
        assertEquals(MotionSensitivity.HIGH, coordinator.state.value.draft.motion.sensitivity)

        coordinator.updateMotion(remote.copy(sensitivity = MotionSensitivity.LOW))
        assertFalse(coordinator.refreshMotion(remote.copy(sensitivity = MotionSensitivity.ADVANCED)))
        assertEquals(MotionSensitivity.LOW, coordinator.state.value.draft.motion.sensitivity)
        assertEquals(MotionSensitivity.HIGH, coordinator.state.value.applied.motion.sensitivity)
    }

    private class FakeSettingsRepository : AppSettingsRepository {
        var saved = AppSettingsSnapshot()
        override fun load(): AppSettingsSnapshot = saved
        override fun save(settings: AppSettingsSnapshot) { saved = settings }
    }
}
