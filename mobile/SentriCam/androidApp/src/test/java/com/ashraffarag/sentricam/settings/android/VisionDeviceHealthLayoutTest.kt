package com.ashraffarag.sentricam.settings.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionDeviceHealthLayoutTest {
    @Test
    fun deviceScreenPrioritizesIdentityHealthOperationsAndCapabilities() {
        val layout = source("src/main/res/layout/settings_section_device.xml")

        val identity = layout.indexOf("@string/settings_device_identity_section_title")
        val health = layout.indexOf("@string/settings_device_health_section_title")
        val operations = layout.indexOf("@string/settings_device_operations_section_title")
        val capabilities = layout.indexOf("@string/settings_device_capabilities_section_title")
        val technical = layout.indexOf("android:id=\"@+id/settings_device_technical_details\"")

        assertTrue(identity in 0 until health)
        assertTrue(health in 0 until operations)
        assertTrue(operations in 0 until capabilities)
        assertTrue(capabilities in 0 until technical)
    }

    @Test
    fun healthRowsUseExistingDeviceSnapshotDataAndSemanticColors() {
        val layout = source("src/main/res/layout/settings_section_device.xml")
        val activity = source(
            "src/main/java/com/ashraffarag/sentricam/settings/android/AppSettingsActivity.kt",
        )

        listOf(
            "settings_device_battery",
            "settings_device_storage",
            "settings_device_camera",
            "settings_device_monitoring",
            "settings_device_recording",
            "settings_device_motion",
        ).forEach { assertTrue(layout.contains("@+id/$it")) }
        listOf(
            "snapshot.battery",
            "snapshot.storage",
            "snapshot.camera",
            "snapshot.monitoringState",
            "snapshot.recordingState",
            "snapshot.motionState",
            "R.color.vision_status_healthy",
            "R.color.vision_status_motion",
            "R.color.vision_status_critical",
            "R.color.vision_status_offline",
        ).forEach { assertTrue(activity.contains(it)) }
    }

    @Test
    fun developerDetailsStartCollapsedAndSurviveRecreation() {
        val layout = source("src/main/res/layout/settings_section_device.xml")
        val activity = source(
            "src/main/java/com/ashraffarag/sentricam/settings/android/AppSettingsActivity.kt",
        )
        val detailTag = Regex(
            "<com\\.google\\.android\\.material\\.card\\.MaterialCardView[^>]*settings_device_technical_details[^>]*>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(layout)?.value.orEmpty()

        assertTrue(detailTag.contains("android:visibility=\"gone\""))
        assertTrue(activity.contains("STATE_DEVICE_DETAILS"))
        assertTrue(activity.contains("outState.putBoolean(STATE_DEVICE_DETAILS"))
        assertTrue(activity.contains("savedInstanceState?.getBoolean(STATE_DEVICE_DETAILS)"))
        assertTrue(activity.contains("renderDeviceDetails(root)"))
    }

    @Test
    fun deviceLayoutUsesSharedVisionPrimitivesAndDirectionSafeTokens() {
        val layout = source("src/main/res/layout/settings_section_device.xml")
        val styles = source("src/main/res/values/styles.xml")

        assertTrue(layout.contains("@style/Widget.SentriCam.Vision.TextField"))
        assertTrue(layout.contains("@style/Widget.SentriCam.Vision.HealthRow"))
        assertTrue(layout.contains("@style/Widget.SentriCam.Vision.SettingsSection.Healthy"))
        assertTrue(styles.contains("name=\"Widget.SentriCam.Vision.HealthRow\""))
        listOf("layout_marginLeft", "layout_marginRight", "paddingLeft", "paddingRight")
            .forEach { assertFalse(layout.contains(it)) }
        assertFalse(Regex("=\"[0-9]+dp\"").containsMatchIn(layout))
    }

    private fun source(relativePath: String): String = File(relativePath).readText()
}
