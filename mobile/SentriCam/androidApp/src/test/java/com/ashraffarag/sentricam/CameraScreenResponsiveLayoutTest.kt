package com.ashraffarag.sentricam

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraScreenResponsiveLayoutTest {
    @Test
    fun operationalStatesUseIndependentFloatingVisionOverlays() {
        val layout = source("src/main/res/layout/activity_main.xml")
        val overlays = element(layout, "operational_status_cluster", "time_validation_panel")

        assertFalse(layout.contains("<HorizontalScrollView"))
        assertTrue(overlays.contains("android:id=\"@+id/realtime_status\""))
        assertTrue(overlays.contains("android:id=\"@+id/live_status\""))
        assertTrue(overlays.contains("android:id=\"@+id/motion_status\""))
        assertTrue(
            Regex("style=\"@style/Widget.SentriCam.Vision.CameraOverlayChip\"")
                .findAll(overlays)
                .count() == 3,
        )
        val identityBar = element(layout, "camera_toolbar", "operational_status_cluster")
        assertTrue(identityBar.contains("<androidx.constraintlayout.widget.ConstraintLayout"))
        assertFalse(identityBar.contains("MaterialCardView"))
    }

    @Test
    fun languageActionStaysOnThePhysicalRightInBothLayoutDirections() {
        val layout = source("src/main/res/layout/activity_main.xml")
        val toolbar = element(layout, "camera_toolbar", "operational_status_cluster")
        val language = element(toolbar, "language_button", "</androidx.constraintlayout.widget.ConstraintLayout>")

        assertTrue(toolbar.contains("android:layoutDirection=\"ltr\""))
        assertTrue(language.contains("app:layout_constraintRight_toRightOf=\"parent\""))
        assertFalse(language.contains("layout_constraintStart_to"))
        assertFalse(language.contains("layout_constraintEnd_to"))
        assertFalse(language.contains("translationX"))
    }

    @Test
    fun motionPanelWrapsWithinThePreviewSafeAreaAndKeepsTheExistingController() {
        val layout = source("src/main/res/layout/activity_main.xml")
        val motion = element(layout, "motion_status", "time_validation_panel")
        val motionView = source("src/main/res/layout/view_motion_status.xml")
        val activity = source("src/main/java/com/ashraffarag/sentricam/MainActivity.kt")

        assertTrue(motion.contains("android:layout_width=\"0dp\""))
        assertTrue(motion.contains("app:layout_constraintRight_toLeftOf=\"@id/camera_control_rail\""))
        assertTrue(motionView.contains("android:maxLines=\"3\""))
        assertTrue(motionView.contains("android:breakStrategy=\"simple\""))
        assertTrue(motionView.contains("android:textDirection=\"ltr\""))
        assertTrue(activity.contains("MotionActionController("))
        assertTrue(activity.contains("primaryButton = binding.motionSettingsButton"))
    }

    @Test
    fun zoomAndFlashUseAPhysicalRightVerticalVisionRail() {
        val layout = source("src/main/res/layout/activity_main.xml")
        val rail = element(
            layout,
            "android:id=\"@+id/camera_control_rail\"",
            "<com.ashraffarag.sentricam.recording.engine.android.ui.RecordingEngineStatusView",
        )
        val zoomPosition = rail.indexOf("android:id=\"@+id/zoom_slider\"")
        val flashPosition = rail.indexOf("android:id=\"@+id/flash_button\"")

        assertTrue(rail.contains("style=\"@style/Widget.SentriCam.Vision.CameraControlRail\""))
        assertTrue(rail.contains("app:layout_constraintRight_toRightOf=\"parent\""))
        assertTrue(rail.contains("android:layout_margin=\"@dimen/vision_space_12\""))
        assertFalse(rail.contains("layout_constraintEnd_toEndOf"))
        assertTrue(rail.contains("android:rotation=\"-90\""))
        assertTrue(rail.contains("android:layoutDirection=\"ltr\""))
        assertTrue(zoomPosition >= 0)
        assertTrue(flashPosition > zoomPosition)
        assertTrue(rail.contains("app:icon=\"@drawable/ic_flash_off_24\""))
    }

    @Test
    fun recordingControlsUseAConstraintCenteredDeterministicShutter() {
        val layout = source("src/main/res/layout/activity_main.xml")
        val dock = element(layout, "recording_panel", "status_panel")
        val shutter = element(layout, "recording_button", "camera_switch_button")
        val activity = source("src/main/java/com/ashraffarag/sentricam/MainActivity.kt")

        assertTrue(dock.contains("com.google.android.material.card.MaterialCardView"))
        assertTrue(dock.contains(
            "style=\"@style/Widget.SentriCam.Vision.CameraControlDock\"",
        ))
        assertTrue(dock.contains("android:layout_width=\"0dp\""))
        assertTrue(dock.contains("android:layout_height=\"@dimen/vision_camera_dock_height\""))
        assertTrue(dock.contains(
            "app:layout_constraintWidth_max=\"@dimen/vision_camera_dock_max_width\"",
        ))
        assertTrue(dock.contains("<androidx.constraintlayout.widget.ConstraintLayout"))
        assertTrue(shutter.contains("<androidx.appcompat.widget.AppCompatImageButton"))
        assertTrue(shutter.contains("style=\"@style/Widget.SentriCam.Vision.CameraShutter\""))
        assertTrue(shutter.contains("android:layout_width=\"@dimen/vision_camera_shutter_size\""))
        assertTrue(shutter.contains("android:layout_height=\"@dimen/vision_camera_shutter_size\""))
        assertTrue(shutter.contains("app:layout_constraintStart_toStartOf=\"parent\""))
        assertTrue(shutter.contains("app:layout_constraintEnd_toEndOf=\"parent\""))
        assertTrue(shutter.contains("app:layout_constraintTop_toTopOf=\"parent\""))
        assertTrue(shutter.contains("app:layout_constraintBottom_toBottomOf=\"parent\""))
        assertTrue(shutter.contains("app:srcCompat=\"@drawable/vision_camera_shutter_inner\""))
        assertTrue(shutter.contains("app:tint=\"@color/vision_action_recording_background\""))
        assertFalse(shutter.contains("app:icon="))
        assertFalse(shutter.contains("android:text="))
        assertFalse(shutter.contains("layout_marginStart"))
        assertFalse(shutter.contains("layout_marginEnd"))
        assertFalse(shutter.contains("layout_constraintStart_toEndOf=\"@id/"))
        assertFalse(shutter.contains("layout_constraintEnd_toStartOf=\"@id/"))
        assertFalse(activity.contains("binding.recordingButton.updateLayoutParams"))
        assertTrue(activity.contains(
            "binding.recordingButton.isSelected = buttonText == R.string.recording_stop",
        ))
        assertTrue(activity.contains("binding.recordingButton.imageTintList = ColorStateList("))
        assertFalse(activity.contains("binding.recordingButton.setIconResource"))
        assertFalse(activity.contains("buttonColor = R.color.vision_brand_primary"))
    }

    @Test
    fun shutterDrawableStatesUseFixedCenteredCircleAndRoundedSquareGeometry() {
        val style = source("src/main/res/values/styles.xml")
        val selector = source("src/main/res/drawable/vision_camera_shutter_inner.xml")
        val idle = source("src/main/res/drawable/vision_camera_shutter_inner_idle.xml")
        val recording = source(
            "src/main/res/drawable/vision_camera_shutter_inner_recording.xml",
        )
        val outer = source("src/main/res/drawable/vision_camera_shutter_outer.xml")

        assertTrue(style.contains("<item name=\"android:scaleType\">center</item>"))
        assertTrue(style.contains(
            "<item name=\"android:background\">@drawable/vision_camera_shutter_outer</item>",
        ))
        assertTrue(selector.contains("android:state_selected=\"true\""))
        assertTrue(selector.contains("@drawable/vision_camera_shutter_inner_recording"))
        assertTrue(selector.contains("@drawable/vision_camera_shutter_inner_idle"))
        assertTrue(idle.contains("android:shape=\"oval\""))
        assertTrue(recording.contains("android:shape=\"rectangle\""))
        assertTrue(recording.contains("@dimen/vision_camera_shutter_stop_radius"))
        listOf(idle, recording).forEach { drawable ->
            assertTrue(drawable.contains("android:width=\"@dimen/vision_camera_shutter_inner_size\""))
            assertTrue(drawable.contains("android:height=\"@dimen/vision_camera_shutter_inner_size\""))
            assertFalse(drawable.contains("inset"))
        }
        assertTrue(outer.contains("android:shape=\"oval\""))
        assertTrue(outer.contains("@dimen/vision_camera_shutter_stroke"))
    }

    @Test
    fun approvedDockKeepsProminentSettingsAndRemovesMoreWithoutLosingMotionSettings() {
        val layout = source("src/main/res/layout/activity_main.xml")
        val dock = element(layout, "recording_panel", "status_panel")
        val settings = element(layout, "settings_button", "recordings_button")
        val activity = source("src/main/java/com/ashraffarag/sentricam/MainActivity.kt")

        assertTrue(dock.contains("android:id=\"@+id/recordings_button\""))
        assertTrue(dock.contains("android:id=\"@+id/camera_switch_button\""))
        assertTrue(dock.contains("android:id=\"@+id/settings_button\""))
        assertTrue(settings.contains("style=\"@style/Widget.SentriCam.Vision.IconAction.Prominent\""))
        assertTrue(settings.contains(
            "android:layout_width=\"@dimen/vision_prominent_icon_action_size\"",
        ))
        assertTrue(settings.contains(
            "android:layout_height=\"@dimen/vision_prominent_icon_action_size\"",
        ))
        assertTrue(settings.contains("app:icon=\"@drawable/ic_settings_24\""))
        assertTrue(settings.contains("app:layout_constraintEnd_toEndOf=\"parent\""))
        assertFalse(layout.contains("camera_actions_button"))
        assertFalse(layout.contains("camera_actions_panel"))
        assertFalse(layout.contains("motion_settings_fallback_button"))
        assertFalse(layout.contains("ic_more_vert_24"))
        assertTrue(activity.contains("settingsFallbackButton = null"))
        assertTrue(activity.contains("openUnifiedSettings(SettingsDestination.motion())"))
        assertTrue(activity.contains("openUnifiedSettings(SettingsDestination.general())"))
    }

    @Test
    fun pairingPromptIsACompactContextBannerNotACentralCard() {
        val layout = source("src/main/res/layout/activity_main.xml")
        val pairing = element(layout, "first_run_panel", "</androidx.constraintlayout.widget.ConstraintLayout>")

        assertTrue(pairing.contains(
            "style=\"@style/Widget.SentriCam.Vision.CameraOverlayChip\"",
        ))
        assertTrue(pairing.contains(
            "app:layout_constraintTop_toBottomOf=\"@id/motion_status\"",
        ))
        assertFalse(pairing.contains("app:layout_constraintBottom_toBottomOf=\"parent\""))
        assertTrue(pairing.contains("android:maxLines=\"1\""))
        assertTrue(pairing.contains("android:id=\"@+id/first_run_manual\""))
        assertTrue(pairing.contains("android:id=\"@+id/first_run_scan\""))
        assertTrue(pairing.contains("app:icon=\"@drawable/ic_qr_scan_24\""))
    }

    @Test
    fun standaloneLiveUsesOptionalPairingAndConnectionSettingsExposeBothExistingRoutes() {
        val activity = source("src/main/java/com/ashraffarag/sentricam/MainActivity.kt")
        val settingsActivity = source(
            "src/main/java/com/ashraffarag/sentricam/settings/android/AppSettingsActivity.kt",
        )
        val connection = source("src/main/res/layout/settings_section_system.xml")
        val strings = source("src/main/res/values/strings.xml")

        assertTrue(activity.contains("CameraOperatingMode.Standalone"))
        assertTrue(activity.contains("binding.firstRunPanel.visibility"))
        assertTrue(strings.contains("name=\"camera_operating_mode_local\">Local camera"))
        assertTrue(strings.contains("name=\"first_run_pairing_message\">Optional:"))
        assertFalse(strings.contains("name=\"first_run_title\">Connect this camera"))
        assertTrue(connection.contains("android:id=\"@+id/settings_operating_mode\""))
        assertTrue(connection.contains("android:id=\"@+id/settings_hub_status\""))
        assertTrue(connection.contains("android:id=\"@+id/settings_scan_hub_qr\""))
        assertTrue(connection.contains("android:id=\"@+id/settings_register_device\""))
        assertTrue(settingsActivity.contains("QrPairingScannerActivity.intent(this)"))
        assertTrue(settingsActivity.contains("deviceRuntime.registration.pairNow(payload)"))
    }

    @Test
    fun primaryRealtimeAndLiveStatesNeverWrapOrEllipsize() {
        val realtime = source("src/main/res/layout/view_realtime_status.xml")
        val live = source("src/main/res/layout/view_live_status.xml")
        val realtimeState = element(realtime, "realtime_status_text", "realtime_reconnect")
        val liveState = element(live, "live_status_text", "</LinearLayout>")

        assertTrue(realtimeState.contains("android:maxLines=\"1\""))
        assertTrue(realtimeState.contains("android:hyphenationFrequency=\"none\""))
        assertFalse(realtimeState.contains("android:ellipsize="))
        assertTrue(liveState.contains("android:maxLines=\"1\""))
        assertTrue(liveState.contains("android:hyphenationFrequency=\"none\""))
        assertFalse(liveState.contains("android:ellipsize="))
    }

    @Test
    fun diagnosticsAndTimeActionCannotConsumePrimaryStatusWidth() {
        val realtime = source("src/main/res/layout/view_realtime_status.xml")
        val camera = source("src/main/res/layout/activity_main.xml")
        val activity = source("src/main/java/com/ashraffarag/sentricam/MainActivity.kt")
        val detailAction = element(realtime, "realtime_detail_toggle", "</LinearLayout>")
        val warning = element(camera, "time_validation_panel", "recording_engine_status")

        assertTrue(detailAction.contains("app:icon=\"@drawable/ic_info_24\""))
        assertTrue(detailAction.contains("android:text=\"\""))
        assertFalse(detailAction.contains("android:text=\"@string/realtime_show_details\""))
        assertTrue(warning.contains("android:maxLines=\"2\""))
        assertTrue(warning.contains("app:icon=\"@drawable/ic_date_time_24\""))
        assertTrue(warning.contains("android:text=\"\""))
        assertTrue(activity.contains("DeviceTimeValidationAction.RETRY"))
        assertTrue(activity.contains("R.drawable.ic_refresh_24"))
        assertTrue(activity.contains("DeviceTimeValidationAction.OPEN_DATE_TIME_SETTINGS"))
        assertTrue(activity.contains("Settings.ACTION_DATE_SETTINGS"))
    }

    @Test
    fun cameraPreviewStillOwnsTheEntireViewport() {
        val layout = source("src/main/res/layout/activity_main.xml")
        val preview = element(layout, "preview_view", "preview_timestamp_overlay")
        val timestamp = element(layout, "preview_timestamp_overlay", "camera_top_scrim")

        assertTrue(preview.contains("android:layout_width=\"0dp\""))
        assertTrue(preview.contains("android:layout_height=\"0dp\""))
        assertTrue(preview.contains("app:layout_constraintTop_toTopOf=\"parent\""))
        assertTrue(preview.contains("app:layout_constraintBottom_toBottomOf=\"parent\""))
        assertTrue(timestamp.contains(
            "app:layout_constraintBottom_toTopOf=\"@id/recording_engine_status\"",
        ))
        assertTrue(timestamp.contains(
            "app:layout_constraintRight_toLeftOf=\"@id/camera_control_rail\"",
        ))
        assertTrue(timestamp.contains(
            "app:layout_constraintTop_toBottomOf=\"@id/camera_overlay_safe_top\"",
        ))
        assertTrue(timestamp.contains("android:layout_marginBottom=\"@dimen/vision_space_8\""))
        assertFalse(timestamp.contains("app:layout_constraintBottom_toBottomOf=\"parent\""))
        assertFalse(timestamp.contains("translationY"))
        assertTrue(layout.contains("@drawable/vision_camera_top_scrim"))
        assertTrue(layout.contains("@drawable/vision_camera_bottom_scrim"))
    }

    @Test
    fun liveOmitsSavedPathDetailWhileRecordingSettingsRetainStorageLocation() {
        val activity = source("src/main/java/com/ashraffarag/sentricam/MainActivity.kt")
        val live = source("src/main/res/layout/activity_main.xml")
        val settings = source("src/main/res/layout/settings_section_recording.xml")

        assertFalse(activity.contains("R.string.recording_saved_path"))
        assertFalse(live.contains("android:text=\"@string/recording_no_result\""))
        assertTrue(settings.contains("@string/settings_recording_storage_location"))
        assertTrue(settings.contains("android:id=\"@+id/settings_storage_value\""))
    }

    @Test
    fun landscapeHidesTheDuplicateEngineSummaryToPreventRecordingOverlap() {
        val defaults = source("src/main/res/values/bools.xml")
        val landscape = source("src/main/res/values-land/bools.xml")
        val statusView = source(
            "src/main/java/com/ashraffarag/sentricam/recording/engine/android/ui/RecordingEngineStatusView.kt",
        )

        assertTrue(defaults.contains("<bool name=\"show_recording_engine_summary\">true</bool>"))
        assertTrue(landscape.contains("<bool name=\"show_recording_engine_summary\">false</bool>"))
        assertTrue(landscape.contains("<bool name=\"show_recording_result\">false</bool>"))
        assertTrue(statusView.contains("resources.getBoolean(R.bool.show_recording_engine_summary)"))
        assertTrue(source("src/main/java/com/ashraffarag/sentricam/MainActivity.kt").contains(
            "resources.getBoolean(R.bool.show_recording_result)",
        ))
        val activity = source("src/main/java/com/ashraffarag/sentricam/MainActivity.kt")
        assertTrue(activity.contains("override fun onConfigurationChanged"))
        assertTrue(activity.contains("applyResponsiveLayoutResources()"))
        assertTrue(activity.contains("R.dimen.recording_panel_bottom_margin"))
        val landscapeDimensions = source("src/main/res/values-land/dimens.xml")
        assertTrue(landscapeDimensions.contains("name=\"recording_panel_bottom_margin\">8dp"))
        assertTrue(landscapeDimensions.contains("name=\"recording_panel_padding\">4dp"))
    }

    private fun element(source: String, id: String, following: String): String {
        val start = source.indexOf(id)
        require(start >= 0) { "Missing $id" }
        val end = source.indexOf(following, start).takeIf { it >= 0 } ?: source.length
        return source.substring((start - 240).coerceAtLeast(0), end)
    }

    private fun source(relativePath: String): String = File(relativePath).readText()
}
