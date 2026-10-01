package com.ashraffarag.sentricam.live.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveOrientationArchitectureTest {
    @Test
    fun everyCameraOwnerUsesTheSharedAuthoritativeRotationMonitor() {
        val activityCamera = source(
            "src/main/java/com/ashraffarag/sentricam/camera/android/CameraXPreviewController.kt",
        )
        val monitoringCamera = source(
            "src/main/java/com/ashraffarag/sentricam/monitoring/android/MonitoringCameraSession.kt",
        )
        val liveCamera = source(
            "src/main/java/com/ashraffarag/sentricam/live/android/LiveCameraHostSession.kt",
        )

        listOf(activityCamera, monitoringCamera, liveCamera).forEach { cameraOwner ->
            assertTrue(cameraOwner.contains("CameraTargetRotationMonitor"))
            assertFalse(cameraOwner.contains("updateTargetRotation(Surface.ROTATION_0)"))
        }
    }

    @Test
    fun monitorUsesPhysicalOrientationWithoutDependingOnOemLockSettings() {
        val monitor = source(
            "src/main/java/com/ashraffarag/sentricam/camera/android/CameraTargetRotationMonitor.kt",
        )

        assertTrue(monitor.contains("Settings.System.ACCELEROMETER_ROTATION"))
        assertTrue(monitor.contains("OrientationEventListener"))
        assertTrue(monitor.contains("CameraTargetRotationPolicy.select"))
        assertTrue(monitor.contains("PhysicalOrientationQuantizer"))
        val policy = source(
            "src/main/java/com/ashraffarag/sentricam/camera/domain/CameraTargetRotationPolicy.kt",
        )
        assertTrue(policy.contains("physicalRotation ?: displayRotation"))
    }

    @Test
    fun rotationCallbacksOnlyUpdateUseCasesWithoutRebindingCamera() {
        val monitoringCallback = functionBody(
            source("src/main/java/com/ashraffarag/sentricam/monitoring/android/MonitoringCameraSession.kt"),
            "private fun updateTargetRotation(rotation: Int)",
        )
        val liveCallback = functionBody(
            source("src/main/java/com/ashraffarag/sentricam/live/android/LiveCameraHostSession.kt"),
            "private fun updateTargetRotation(rotation: Int)",
        )

        listOf(monitoringCallback, liveCallback).forEach { callback ->
            assertTrue(callback.contains("targetRotation"))
            assertFalse(callback.contains("bindToLifecycle"))
            assertFalse(callback.contains("unbind"))
        }
    }

    @Test
    fun publisherCarriesRotationMetadataWithoutPixelRotationOrTrackRecreation() {
        val publisher = source(
            "src/main/java/com/ashraffarag/sentricam/live/android/AndroidWebRtcPublisher.kt",
        )
        val publishFrame = functionBody(publisher, "private fun publishFrame(frame: CameraFrame)")

        assertTrue(publishFrame.contains("VideoFrame(buffer, orientation.rotationDegrees"))
        assertFalse(publishFrame.contains("rotate("))
        assertFalse(publishFrame.contains("addTrack("))
        assertEquals(1, Regex("\\.addTrack\\(").findAll(publisher).count())
    }

    @Test
    fun monitoringOwnerBindsPreviewToTheSharedSurfaceAlongsideAnalysis() {
        val monitoring = source(
            "src/main/java/com/ashraffarag/sentricam/monitoring/android/MonitoringCameraSession.kt",
        )

        assertTrue(monitoring.contains("Preview.Builder()"))
        assertTrue(monitoring.contains("observeSurfaceProvider"))
        assertTrue(monitoring.contains("localPreview.surfaceProvider = surface"))
        assertTrue(monitoring.contains(".addUseCase(localPreview)"))
        assertTrue(monitoring.contains("previewUseCase?.targetRotation = rotation"))
    }

    @Test
    fun monitoringKeepsPreviewAndVideoCaptureBoundDuringRecording() {
        val monitoring = source(
            "src/main/java/com/ashraffarag/sentricam/monitoring/android/MonitoringCameraSession.kt",
        )
        val recordingControl = source(
            "src/main/java/com/ashraffarag/sentricam/cameracontrol/android/RecordingCommandCameraControl.kt",
        )

        assertTrue(monitoring.contains(".addUseCase(localPreview)"))
        assertTrue(monitoring.contains(".addUseCase(recorder.videoCapture)"))
        assertTrue(monitoring.contains(".addUseCase(detector.imageAnalysis.useCase)"))
        assertFalse(recordingControl.contains("bindToLifecycle"))
        assertFalse(recordingControl.contains("unbind"))
        assertFalse(recordingControl.contains("ProcessCameraProvider"))
    }

    @Test
    fun previewVisibilityNeverStartsStopsOrRebindsTheCamera() {
        val preview = source(
            "src/main/java/com/ashraffarag/sentricam/live/android/AndroidPreviewController.kt",
        )
        val activity = source("src/main/java/com/ashraffarag/sentricam/MainActivity.kt")

        assertFalse(preview.contains("bindToLifecycle"))
        assertFalse(preview.contains("unbind"))
        assertFalse(preview.contains("start("))
        assertFalse(preview.contains("stop("))
        assertTrue(preview.contains("surfaceProvider.takeIf { mutableVisible.value }"))
        assertTrue(activity.contains("renderLivePreviewVisibility(presentation)"))
        assertTrue(activity.contains("else -> binding.statusPanel.visibility = View.GONE"))
        assertTrue(activity.contains("!visible -> showCameraStatus(R.string.live_preview_hidden)"))
        assertTrue(activity.contains("presentation == \"dimmed\""))
    }

    @Test
    fun timestampOverlayUsesThePreviewButStopsAboveRecordingChrome() {
        val layout = source("src/main/res/layout/activity_main.xml")
        val previewPosition = layout.indexOf("android:id=\"@+id/preview_view\"")
        val overlayPosition = layout.indexOf("android:id=\"@+id/preview_timestamp_overlay\"")
        val overlay = layout
            .substringAfter("android:id=\"@+id/preview_timestamp_overlay\"")
            .substringBefore("/>")

        assertTrue(previewPosition >= 0)
        assertTrue(overlayPosition > previewPosition)
        assertTrue(overlay.contains(
            "app:layout_constraintTop_toBottomOf=\"@id/camera_overlay_safe_top\"",
        ))
        assertTrue(overlay.contains("android:layout_marginTop=\"@dimen/vision_space_8\""))
        assertTrue(overlay.contains(
            "app:layout_constraintBottom_toTopOf=\"@id/recording_engine_status\"",
        ))
        assertTrue(overlay.contains(
            "app:layout_constraintRight_toLeftOf=\"@id/camera_control_rail\"",
        ))
        assertTrue(overlay.contains("android:layout_marginBottom=\"@dimen/vision_space_8\""))
        assertFalse(overlay.contains("app:layout_constraintBottom_toBottomOf=\"parent\""))
        assertFalse(overlay.contains("app:layout_constraintBottom_toBottomOf=\"@id/preview_view\""))
        assertTrue(layout.contains("android:id=\"@+id/camera_overlay_safe_top\""))
        assertTrue(layout.contains("app:barrierAllowsGoneWidgets=\"false\""))
        assertTrue(layout.contains(
            "camera_toolbar,operational_status_cluster,motion_status,time_validation_panel,first_run_panel",
        ))
    }

    private fun functionBody(source: String, declaration: String): String =
        source.substringAfter(declaration).substringBefore("\n    private ")

    private fun source(relativePath: String): String = File(relativePath).readText()
}
