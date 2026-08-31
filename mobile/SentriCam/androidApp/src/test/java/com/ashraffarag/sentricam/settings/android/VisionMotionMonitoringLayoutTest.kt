package com.ashraffarag.sentricam.settings.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionMotionMonitoringLayoutTest {
    @Test
    fun motionSettingsAreGroupedIntoAutomationSensitivityAndTimingSections() {
        val layout = source("src/main/res/layout/settings_section_motion.xml")

        assertTrue(layout.contains("@style/Widget.SentriCam.Vision.SettingsSection.Motion"))
        assertTrue(layout.contains("@string/motion_automation_section_title"))
        assertTrue(layout.contains("@string/motion_sensitivity_title"))
        assertTrue(layout.contains("@string/motion_timing_section_title"))
        assertTrue(layout.contains("@string/motion_trigger_delay_summary"))
        assertTrue(layout.contains("@string/motion_stop_delay_summary"))
        assertTrue(layout.contains("@string/motion_cooldown_summary"))
    }

    @Test
    fun motionControlsRetainTheirBehaviorIdsAndAmberSemantics() {
        val layout = source("src/main/res/layout/settings_section_motion.xml")

        listOf(
            "settings_motion_enabled",
            "settings_motion_sensitivity",
            "settings_advanced_sensitivity_slider",
            "settings_motion_trigger",
            "settings_motion_hold",
            "settings_motion_cooldown",
        ).forEach { id -> assertTrue(layout.contains("android:id=\"@+id/$id\"")) }
        assertTrue(layout.contains("@color/vision_status_motion"))
        assertTrue(layout.contains("@style/Widget.SentriCam.Vision.SegmentedControl"))
        assertFalse(layout.contains("Widget.SentriCam.MotionSettings.Chip"))
    }

    @Test
    fun monitoringSeparatesOperationNotificationsAndDebugDetails() {
        val layout = source("src/main/res/layout/settings_section_monitoring.xml")
        val activity = source(
            "src/main/java/com/ashraffarag/sentricam/settings/android/AppSettingsActivity.kt",
        )

        assertTrue(layout.contains("@style/Widget.SentriCam.Vision.SettingsSection.Healthy"))
        assertTrue(layout.contains("@string/monitoring_service_section_title"))
        assertTrue(layout.contains("@string/monitoring_notification_options"))
        assertTrue(layout.contains("android:id=\"@+id/settings_monitoring_debug_group\""))
        assertTrue(layout.contains("android:visibility=\"gone\""))
        assertTrue(activity.contains("R.id.settings_monitoring_debug_group"))
        assertTrue(activity.contains("if (BuildConfig.DEBUG) View.VISIBLE else View.GONE"))
    }

    @Test
    fun monitoringRetainsAllExistingSettingsControls() {
        val layout = source("src/main/res/layout/settings_section_monitoring.xml")

        listOf(
            "settings_monitoring_enabled",
            "settings_monitoring_auto_start",
            "settings_notification_motion",
            "settings_notification_recording",
            "settings_notification_battery",
            "settings_notification_storage",
            "settings_monitoring_restart_failure",
            "settings_monitoring_verbose",
        ).forEach { id -> assertTrue(layout.contains("android:id=\"@+id/$id\"")) }
    }

    @Test
    fun phaseThreeLayoutsUseDirectionSafeTokenizedSpacingAndTouchTargets() {
        val layouts = listOf(
            source("src/main/res/layout/settings_section_motion.xml"),
            source("src/main/res/layout/settings_section_monitoring.xml"),
        ).joinToString("\n")

        listOf("layout_marginLeft", "layout_marginRight", "paddingLeft", "paddingRight")
            .forEach { assertFalse(layouts.contains(it)) }
        assertFalse(Regex("=\"[0-9]+dp\"").containsMatchIn(layouts))
        assertTrue(layouts.contains("@style/Widget.SentriCam.Vision.SettingsSwitch"))
    }

    private fun source(relativePath: String): String = File(relativePath).readText()
}
