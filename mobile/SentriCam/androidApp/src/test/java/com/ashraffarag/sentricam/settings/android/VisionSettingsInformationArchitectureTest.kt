package com.ashraffarag.sentricam.settings.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionSettingsInformationArchitectureTest {
    @Test
    fun settingsHomeHasExactlyTheSixApprovedPrimaryCategories() {
        val activity = source(
            "src/main/java/com/ashraffarag/sentricam/settings/android/AppSettingsActivity.kt",
        )
        val items = activity.substringAfter("SETTINGS_HOME_ITEMS = listOf(")
            .substringBefore("private val SMART_FEATURES")

        assertEquals(6, Regex("SettingsHomeItem\\(").findAll(items).count())
        listOf(
            "SettingsSection.CAMERA",
            "SettingsSection.RECORDING",
            "SettingsSection.MOTION_DETECTION",
            "SettingsSection.MONITORING",
            "SettingsSection.DEVICE",
            "SettingsSection.SYSTEM_CONNECTIVITY",
        ).forEach { assertTrue(items.contains(it)) }
        assertFalse(items.contains("SettingsSection.SMART_DETECTION"))
    }

    @Test
    fun oldHorizontalTabArchitectureIsAbsentFromEveryResponsiveLayout() {
        val layouts = listOf(
            "src/main/res/layout/activity_app_settings.xml",
            "src/main/res/layout-land/activity_app_settings.xml",
            "src/main/res/layout-sw600dp/activity_app_settings.xml",
        ).map(::source)

        layouts.forEach { layout ->
            assertFalse(layout.contains("HorizontalScrollView"))
            assertFalse(layout.contains("settings_navigation"))
            assertTrue(layout.contains("android:id=\"@+id/settings_content\""))
            assertTrue(layout.contains("@style/Widget.SentriCam.Vision.ScreenToolbar"))
            assertTrue(layout.contains("@style/Widget.SentriCam.Vision.PrimaryAction"))
            assertTrue(layout.contains("@dimen/vision_settings_content_max_width"))
        }
    }

    @Test
    fun sharedSettingsShellAlwaysPlacesContentBelowTheSingleAppBar() {
        val layouts = listOf(
            "src/main/res/layout/activity_app_settings.xml",
            "src/main/res/layout-land/activity_app_settings.xml",
            "src/main/res/layout-sw600dp/activity_app_settings.xml",
        ).map(::source)

        layouts.forEach { layout ->
            val toolbar = layout.substringAfter("android:id=\"@+id/settings_toolbar\"")
                .substringBefore("<FrameLayout")
            val content = layout.substringAfter("android:id=\"@+id/settings_content\"")
                .substringBefore("<com.google.android.material.button.MaterialButton")

            assertTrue(toolbar.contains("android:layout_height=\"@dimen/vision_screen_toolbar_height\""))
            assertTrue(toolbar.contains("app:title=\"@string/app_settings_title\""))
            assertTrue(toolbar.contains("app:navigationContentDescription=\"@string/navigation_back\""))
            assertTrue(content.contains(
                "app:layout_constraintTop_toBottomOf=\"@id/settings_toolbar\"",
            ))
            assertFalse(content.contains("app:layout_constraintTop_toTopOf=\"parent\""))
        }
    }

    @Test
    fun pageTitlesBelongToScrollableContentNotTheFixedHeightToolbar() {
        val activity = source(
            "src/main/java/com/ashraffarag/sentricam/settings/android/AppSettingsActivity.kt",
        )
        val home = source("src/main/res/layout/settings_home.xml")
        val sectionLayouts = listOf(
            "settings_section_camera.xml",
            "settings_section_recording.xml",
            "settings_section_motion.xml",
            "settings_section_monitoring.xml",
            "settings_section_smart.xml",
            "settings_section_device.xml",
            "settings_section_system.xml",
        ).map { source("src/main/res/layout/$it") }

        assertFalse(activity.contains("settingsToolbar.subtitle = getString"))
        assertEquals(2, Regex("binding\\.settingsToolbar\\.subtitle = null").findAll(activity).count())
        assertFalse(home.contains("android:text=\"@string/app_settings_title\""))
        assertTrue(home.contains("android:paddingTop=\"@dimen/vision_space_16\""))
        sectionLayouts.forEach { layout ->
            assertTrue(layout.contains("android:paddingTop=\"@dimen/vision_space_16\""))
            assertTrue(layout.contains(
                "android:textAppearance=\"@style/TextAppearance.SentriCam.Vision.ScreenTitle\"",
            ))
            assertTrue(layout.contains(
                "android:textAppearance=\"@style/TextAppearance.SentriCam.Vision.Supporting\"",
            ))
            assertTrue(layout.contains("android:layout_marginTop=\"@dimen/vision_space_16\""))
        }
    }

    @Test
    fun settingsHomeRowsAreReusableDirectionSafeAndAccessible() {
        val home = source("src/main/res/layout/settings_home.xml")
        val row = source("src/main/res/layout/view_settings_navigation_row.xml")
        val activity = source(
            "src/main/java/com/ashraffarag/sentricam/settings/android/AppSettingsActivity.kt",
        )

        assertTrue(home.contains("android:id=\"@+id/settings_home_navigation\""))
        assertTrue(row.contains("@style/Widget.SentriCam.Vision.SettingsNavigationRow"))
        assertTrue(row.contains("@drawable/ic_chevron_forward_24"))
        assertTrue(activity.contains("row.root.contentDescription"))
        assertTrue(activity.contains("ViewSettingsNavigationRowBinding.inflate"))
        listOf("layout_marginLeft", "layout_marginRight", "paddingLeft", "paddingRight")
            .forEach { assertFalse((home + row).contains(it)) }
    }

    @Test
    fun normalSettingsEntryOpensHomeWhileDirectDestinationsStayDirect() {
        val activity = source(
            "src/main/java/com/ashraffarag/sentricam/settings/android/AppSettingsActivity.kt",
        )

        assertTrue(activity.contains("destination == SettingsDestination.general()"))
        assertTrue(activity.contains("putExtra(EXTRA_SHOW_HOME, true)"))
        assertTrue(activity.contains("putExtra(EXTRA_SECTION, destination.section.name)"))
        assertTrue(activity.contains("if (settingsHomeAvailable) renderSettingsHome() else renderSelectedSection()"))
        assertTrue(activity.contains("if (settingsHomeAvailable && !showingSettingsHome)"))
    }

    @Test
    fun cameraAndRecordingSectionsRetainControlsWithinVisionGroups() {
        val camera = source("src/main/res/layout/settings_section_camera.xml")
        val recording = source("src/main/res/layout/settings_section_recording.xml")

        listOf("settings_camera_lens", "settings_camera_rear", "settings_camera_front", "settings_timestamp_enabled")
            .forEach { assertTrue(camera.contains("@+id/$it")) }
        listOf(
            "settings_recording_quality",
            "settings_segment_duration",
            "settings_audio_enabled",
            "settings_storage_value",
            "settings_storage_warning",
            "settings_simulate_storage_warning",
        ).forEach { assertTrue(recording.contains("@+id/$it")) }
        assertTrue(camera.contains("@style/Widget.SentriCam.Vision.SettingsSection"))
        assertTrue(recording.contains("@style/Widget.SentriCam.Vision.SettingsSection"))
        assertFalse(camera.contains("Widget.SentriCam.MotionSettings.Chip"))
        assertFalse(recording.contains("Widget.SentriCam.MotionSettings.Chip"))
    }

    private fun source(relativePath: String): String = File(relativePath).readText()
}
