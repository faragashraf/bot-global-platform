package com.ashraffarag.sentricam.settings.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionGlobalUiAuditTest {
    @Test
    fun remainingSettingsSurfacesUseSharedVisionPrimitivesAndTokenizedSpacing() {
        val layouts = listOf(
            "src/main/res/layout/settings_section_smart.xml",
            "src/main/res/layout/settings_section_system.xml",
            "src/main/res/layout/view_capability_feature_row.xml",
            "src/main/res/layout/activity_qr_pairing_scanner.xml",
        ).map(::source).joinToString("\n")

        assertTrue(layouts.contains("@style/Widget.SentriCam.Vision.SettingsSection"))
        assertTrue(layouts.contains("@style/Widget.SentriCam.Vision.Surface.Glass"))
        assertTrue(layouts.contains("@style/Widget.SentriCam.Vision.StatusChip.Secondary"))
        assertFalse(Regex("=\"[1-9][0-9]*dp\"").containsMatchIn(layouts))
        listOf("layout_marginLeft", "layout_marginRight", "paddingLeft", "paddingRight")
            .forEach { assertFalse(layouts.contains(it)) }
    }

    @Test
    fun connectionPresentationRetainsEveryExistingActionWithoutClaimingStandaloneBehavior() {
        val layout = source("src/main/res/layout/settings_section_system.xml")

        listOf(
            "settings_server_url_container",
            "settings_test_connection",
            "settings_register_device",
            "settings_forget_registration",
            "settings_signalr_force_reconnect",
        ).forEach { assertTrue(layout.contains("@+id/$it")) }
        assertTrue(layout.contains("@style/Widget.SentriCam.Vision.PrimaryAction"))
        assertTrue(layout.contains("@style/Widget.SentriCam.Vision.DestructiveAction"))
        assertFalse(layout.contains("Standalone", ignoreCase = true))
        assertFalse(layout.contains("optional", ignoreCase = true))
    }

    @Test
    fun capabilityStatusIsTextualAndSemanticallyColored() {
        val layout = source("src/main/res/layout/view_capability_feature_row.xml")
        val view = source(
            "src/main/java/com/ashraffarag/sentricam/settings/android/CapabilityFeatureRowView.kt",
        )

        assertTrue(layout.contains("com.google.android.material.chip.Chip"))
        assertTrue(view.contains("CapabilityAccessKind.AVAILABLE -> R.color.vision_status_healthy"))
        assertTrue(view.contains("CapabilityAccessKind.COMING_SOON"))
        assertTrue(view.contains("R.color.vision_brand_secondary"))
        assertTrue(view.contains("R.string.capability_accessibility"))
    }

    @Test
    fun sectionRenderingStartsAtTheHeadingWithoutAutoFocusingAnInput() {
        val activity = source(
            "src/main/java/com/ashraffarag/sentricam/settings/android/AppSettingsActivity.kt",
        )

        assertTrue(activity.contains("root.isFocusableInTouchMode = true"))
        assertTrue(activity.contains("root.requestFocus()"))
    }

    @Test
    fun settingsBackActionIsLabeledAcrossResponsiveLayouts() {
        listOf(
            "src/main/res/layout/activity_app_settings.xml",
            "src/main/res/layout-land/activity_app_settings.xml",
            "src/main/res/layout-sw600dp/activity_app_settings.xml",
        ).map(::source).forEach { layout ->
            assertTrue(layout.contains("app:navigationContentDescription=\"@string/navigation_back\""))
        }
    }

    @Test
    fun destructiveAndPermissionDialogsUseTheMaterialVisionTheme() {
        val main = source("src/main/java/com/ashraffarag/sentricam/MainActivity.kt")
        val playback = source(
            "src/main/java/com/ashraffarag/sentricam/recording/library/android/ui/VideoPlayerActivity.kt",
        )

        assertTrue(main.contains("MaterialAlertDialogBuilder(this)"))
        assertTrue(playback.contains("MaterialAlertDialogBuilder(this)"))
        assertFalse(main.contains("AlertDialog.Builder(this)"))
        assertFalse(playback.contains("AlertDialog.Builder(this)"))
    }

    @Test
    fun redesignedSettingsHaveEnglishResourcesForTheirVisibleLabels() {
        val english = source("src/main/res/values/strings.xml")

        listOf(
            "app_settings_title",
            "settings_smart_title",
            "settings_system_summary",
            "camera_choice_title",
            "engine_quality_title",
            "engine_storage_value",
            "motion_sensitivity_title",
            "motion_sensitivity_accessibility_value",
            "capability_available",
            "smart_person_name",
        ).forEach { assertTrue(english.contains("name=\"$it\"")) }
    }

    @Test
    fun obsoleteVisualAliasesHaveNoRemainingDefinitionsOrReferences() {
        val resources = File("src/main").walkTopDown()
            .filter { it.isFile && (it.extension == "xml" || it.extension == "kt") }
            .joinToString("\n") { it.readText() }

        assertFalse(resources.contains("Widget.SentriCam.MotionSettings.Chip"))
        assertFalse(resources.contains("SentriCam.Settings.Body"))
    }

    private fun source(relativePath: String): String = File(relativePath).readText()
}
