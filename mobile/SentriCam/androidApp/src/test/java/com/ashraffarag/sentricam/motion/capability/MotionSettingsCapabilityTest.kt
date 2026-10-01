package com.ashraffarag.sentricam.motion.capability

import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig
import com.ashraffarag.sentricam.motion.domain.MotionSensitivity
import com.ashraffarag.sentricam.motion.domain.MotionSensitivityPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionSettingsCapabilityTest {
    @Test
    fun everyInteractiveAndroidMotionControlHasOneParityClassification() {
        val layout = File("src/main/res/layout/settings_section_motion.xml").readText()
        val interactiveTags = Regex(
            "<(?:com\\.google\\.android\\.material\\.materialswitch\\.MaterialSwitch|" +
                "com\\.google\\.android\\.material\\.chip\\.ChipGroup|" +
                "com\\.google\\.android\\.material\\.slider\\.Slider)[^>]*>",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).findAll(layout)
            .mapNotNull { tag -> Regex("android:id=\"@\\+id/([^\"]+)\"").find(tag.value)?.groupValues?.get(1) }
            .toSet()

        val inventory = MotionSettingsParityInventory.userSettings
        assertEquals(interactiveTags, inventory.map { it.androidViewId }.toSet())
        assertEquals(MotionSettingIds.userConfigurable, inventory.map { it.id }.toSet())
        assertEquals(inventory.size, inventory.map { it.id }.distinct().size)
        assertTrue(inventory.all { it.classification == MotionSettingParityClassification.HUB_WRITABLE })
        assertTrue(inventory.none { it.requiresCameraRestart })
    }

    @Test
    fun reportUsesAndroidDefaultsOptionsAndReadOnlyEngineValues() {
        val repository = MemoryRepository()
        val report = DefaultMotionSettingsCapabilityReporter(repository, isDebug = false).report()

        assertEquals(1L, report.version)
        assertFalse(report.settings.enabled)
        assertEquals("medium", report.settings.sensitivity)
        assertEquals(50, report.settings.advancedSensitivity)
        assertEquals(1_000L, report.settings.triggerDelayMillis)
        assertEquals(10_000L, report.settings.stopDelayMillis)
        assertEquals(5_000L, report.settings.cooldownMillis)
        assertEquals(listOf("low", "medium", "high", "advanced"), report.capability(MotionSettingIds.SENSITIVITY).allowedValues)
        assertEquals(listOf("0", "1000", "2000"), report.capability(MotionSettingIds.TRIGGER_DELAY).allowedValues)
        assertEquals(listOf("5000", "10000", "20000", "30000"), report.capability(MotionSettingIds.STOP_DELAY).allowedValues)
        assertEquals(listOf("3000", "5000", "10000"), report.capability(MotionSettingIds.COOLDOWN).allowedValues)
        assertFalse(report.capability(MotionSettingIds.FRAME_INTERVAL).writable)
        assertFalse(report.capability(MotionSettingIds.WARMUP_FRAMES).writable)
        assertEquals("medium", report.effectiveConfiguration?.selectedMode)
        assertEquals("preset", report.effectiveConfiguration?.source)
        assertEquals(0.075, report.effectiveConfiguration?.threshold ?: 0.0, 0.0001)
    }

    @Test
    fun sharedPolicyValidatesTypesRangesOptionsAndAdvancedSemantics() {
        val current = MotionDetectionConfig()
        val advanced = MotionSettingsCapabilityPolicy.apply(
            current,
            MotionSettingIds.ADVANCED_SENSITIVITY,
            MotionSettingValue(number = 72.0),
            isDebug = false,
        )
        assertTrue(advanced is MotionSettingMutationResult.Valid)
        assertEquals(MotionSensitivity.MEDIUM, (advanced as MotionSettingMutationResult.Valid).configuration.sensitivity)
        assertEquals(72, advanced.configuration.advancedSensitivity)

        assertEquals(
            "motion_value_out_of_range",
            (MotionSettingsCapabilityPolicy.apply(current, MotionSettingIds.ADVANCED_SENSITIVITY, MotionSettingValue(number = 101.0), false) as MotionSettingMutationResult.Invalid).code,
        )
        assertEquals(
            "unsupported_motion_value",
            (MotionSettingsCapabilityPolicy.apply(current, MotionSettingIds.COOLDOWN, MotionSettingValue(number = 4_000.0), false) as MotionSettingMutationResult.Invalid).code,
        )
        assertEquals(
            "invalid_motion_value_type",
            (MotionSettingsCapabilityPolicy.apply(current, MotionSettingIds.ENABLED, MotionSettingValue(text = "true"), false) as MotionSettingMutationResult.Invalid).code,
        )
    }

    @Test
    fun customValueIsRetainedUnderPresetAndAppliedOnlyAfterSelectingAdvanced() {
        val repository = MemoryRepository()
        val retained = MotionSettingsCapabilityPolicy.apply(
            repository.load().copy(sensitivity = MotionSensitivity.LOW),
            MotionSettingIds.ADVANCED_SENSITIVITY,
            MotionSettingValue(number = 81.0),
            isDebug = false,
        ) as MotionSettingMutationResult.Valid
        repository.save(retained.configuration)

        val presetReport = DefaultMotionSettingsCapabilityReporter(repository, false).report()
        assertEquals("low", presetReport.settings.sensitivity)
        assertEquals(81, presetReport.settings.advancedSensitivity)
        assertEquals("preset", presetReport.effectiveConfiguration?.source)
        assertEquals(0.120, presetReport.effectiveConfiguration?.threshold ?: 0.0, 0.0001)

        val selected = MotionSettingsCapabilityPolicy.apply(
            repository.load(),
            MotionSettingIds.SENSITIVITY,
            MotionSettingValue(text = "advanced"),
            isDebug = false,
        ) as MotionSettingMutationResult.Valid
        repository.save(selected.configuration)
        val advancedReport = DefaultMotionSettingsCapabilityReporter(repository, false).report()
        assertEquals(81, advancedReport.settings.advancedSensitivity)
        assertEquals("custom", advancedReport.effectiveConfiguration?.source)
        assertEquals(
            MotionSensitivityPolicy.profile(MotionSensitivity.ADVANCED, 81).threshold,
            advancedReport.effectiveConfiguration?.threshold ?: 0.0,
            0.0001,
        )
    }

    @Test
    fun androidAdvancedContainerDefaultsHiddenAndFollowsTheAuthoritativeResolution() {
        val layout = File("src/main/res/layout/settings_section_motion.xml").readText()
        val activity = File("src/main/java/com/ashraffarag/sentricam/settings/android/AppSettingsActivity.kt").readText()
        val containerTag = Regex(
            "<LinearLayout[^>]*settings_advanced_sensitivity_container[^>]*>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(layout)?.value.orEmpty()

        assertTrue(containerTag.contains("android:visibility=\"gone\""))
        assertTrue(activity.contains("config.resolvedSensitivity().advancedSensitivityActive"))
        assertFalse(MotionDetectionConfig(sensitivity = MotionSensitivity.LOW).resolvedSensitivity().advancedSensitivityActive)
        assertTrue(MotionDetectionConfig(sensitivity = MotionSensitivity.ADVANCED).resolvedSensitivity().advancedSensitivityActive)
    }

    @Test
    fun versionedRepositoryRejectsLostUpdatesAndKeepsTheLocalValue() {
        val repository = MemoryRepository()
        val first = repository.saveIfVersion(repository.load().copy(enabled = true), expectedVersion = 1)
        val stale = repository.saveIfVersion(repository.load().copy(cooldownMillis = 10_000), expectedVersion = 1)

        assertTrue(first is MotionSettingsSaveResult.Updated)
        assertTrue(stale is MotionSettingsSaveResult.Stale)
        assertTrue(repository.load().enabled)
        assertEquals(5_000L, repository.load().cooldownMillis)
        assertEquals(2L, repository.loadVersioned().version)
    }

    private fun MotionSettingsDeviceReport.capability(id: String) = capabilities.single { it.id == id }

    private class MemoryRepository : MotionSettingsRepository {
        private var current = VersionedMotionSettings(MotionDetectionConfig(), 1L)
        override fun load() = current.configuration
        override fun loadVersioned() = current
        override fun save(config: MotionDetectionConfig) {
            saveIfVersion(config, null)
        }
        override fun saveIfVersion(config: MotionDetectionConfig, expectedVersion: Long?): MotionSettingsSaveResult {
            if (expectedVersion != null && expectedVersion != current.version) return MotionSettingsSaveResult.Stale(current)
            if (config == current.configuration) return MotionSettingsSaveResult.Unchanged(current)
            current = VersionedMotionSettings(config, current.version + 1)
            return MotionSettingsSaveResult.Updated(current)
        }
    }
}
