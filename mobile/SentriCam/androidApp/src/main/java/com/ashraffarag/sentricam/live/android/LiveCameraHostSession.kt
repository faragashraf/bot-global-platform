package com.ashraffarag.sentricam.live.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.ashraffarag.sentricam.device.android.DeviceRuntime
import com.ashraffarag.sentricam.camera.android.CameraTargetRotationMonitor
import com.ashraffarag.sentricam.live.capability.LiveCameraLease
import com.ashraffarag.sentricam.live.capability.LiveCameraLeaseResult
import com.ashraffarag.sentricam.live.domain.LiveCapabilityReadiness
import com.ashraffarag.sentricam.live.domain.LiveReadinessStates
import com.ashraffarag.sentricam.monitoring.domain.CameraOwner
import com.ashraffarag.sentricam.monitoring.domain.CameraOwnershipResult
import com.ashraffarag.sentricam.motion.android.AndroidMotionFrameSource
import com.ashraffarag.sentricam.recording.settings.android.SharedPreferencesRecordingSettingsRepository
import com.ashraffarag.sentricam.recording.settings.domain.RecordingCamera
import com.ashraffarag.sentricam.cameracontrol.android.AndroidCameraXControl
import com.ashraffarag.sentricam.cameracontrol.android.AndroidCameraControlLogger
import com.ashraffarag.sentricam.cameracontrol.android.AndroidCameraFrameRateResolver
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlValues
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Supplies CameraX frames when no Activity or monitoring pipeline currently owns the camera. */
class LiveCameraHostSession(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val runtime: DeviceRuntime,
    private val preview: AndroidPreviewController,
    private val prepareForeground: () -> Boolean,
    private val releaseForeground: () -> Unit,
) : LiveCameraLease, AutoCloseable {
    private val appContext = context.applicationContext
    private var owned = false
    private var provider: ProcessCameraProvider? = null
    private var frameSource: AndroidMotionFrameSource? = null
    private var previewUseCase: Preview? = null
    private var surfaceSubscription: AutoCloseable? = null
    private var cameraControlHardware: AndroidCameraXControl? = null
    private val rotationMonitor = CameraTargetRotationMonitor(appContext, ::updateTargetRotation)

    val active: Boolean
        get() = owned

    fun readiness(): LiveCapabilityReadiness {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return LiveCapabilityReadiness(LiveReadinessStates.UNAVAILABLE, "camera_permission_missing")
        }
        if (runtime.liveCameraStream.isAttached()) {
            return LiveCapabilityReadiness(LiveReadinessStates.READY)
        }
        return when (runtime.cameraOwnership.owner.value) {
            CameraOwner.NONE,
            CameraOwner.LIVE_VIEW,
            -> LiveCapabilityReadiness(LiveReadinessStates.READY)
            CameraOwner.MONITORING_SERVICE ->
                LiveCapabilityReadiness(LiveReadinessStates.INITIALIZING, "camera_initializing")
            CameraOwner.CAMERA_ACTIVITY ->
                LiveCapabilityReadiness(LiveReadinessStates.UNAVAILABLE, "camera_busy")
        }
    }

    override suspend fun acquire(): LiveCameraLeaseResult {
        if (runtime.liveCameraStream.isAttached()) return LiveCameraLeaseResult.Acquired
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return LiveCameraLeaseResult.Rejected("camera_permission_missing")
        }
        if (!prepareForeground()) return LiveCameraLeaseResult.Rejected("foreground_camera_start_failed")
        when (runtime.cameraOwnership.acquire(CameraOwner.LIVE_VIEW, OWNER_TOKEN)) {
            CameraOwnershipResult.Acquired,
            CameraOwnershipResult.AlreadyOwned,
            -> Unit
            is CameraOwnershipResult.Rejected -> {
                releaseForeground()
                return LiveCameraLeaseResult.Rejected("camera_busy")
            }
        }
        return try {
            val control = runtime.cameraControlSettings()
            val dimensions = control.resolution.split('x', limit = 2)
            val effectiveFramesPerSecond = when (control.nightProfile) {
                CameraControlValues.NIGHT -> minOf(control.framesPerSecond, 15)
                CameraControlValues.INDOOR -> minOf(control.framesPerSecond, 24)
                else -> control.framesPerSecond
            }.coerceIn(1, 60)
            val source = AndroidMotionFrameSource(
                targetWidth = dimensions.getOrNull(0)?.toIntOrNull() ?: 1280,
                targetHeight = dimensions.getOrNull(1)?.toIntOrNull() ?: 720,
                targetFrameRateRange = AndroidCameraFrameRateResolver(appContext).resolve(
                    control.lens,
                    effectiveFramesPerSecond,
                ),
            )
            rotationMonitor.start()
            val targetRotation = rotationMonitor.currentTargetRotation()
            val localPreview = Preview.Builder().setTargetRotation(targetRotation).build()
            LiveViewDiagnostics.log(LiveViewDiagnostics.PREVIEW) {
                "event=use_case_created owner=live-host targetRotation=$targetRotation preview=${localPreview.identity()}"
            }
            surfaceSubscription = preview.observeSurfaceProvider { surface ->
                ContextCompat.getMainExecutor(appContext).execute {
                    LiveViewDiagnostics.log(LiveViewDiagnostics.PREVIEW) {
                        "event=surface_assigned owner=live-host preview=${localPreview.identity()} " +
                            "provider=${surface.identity()} bound=${provider != null}"
                    }
                    localPreview.surfaceProvider = surface
                }
            }
            frameSource = source
            previewUseCase = localPreview
            owned = true
            val binding = bind(source, localPreview)
            provider = binding.provider
            cameraControlHardware = AndroidCameraXControl(
                binding.camera,
                preview,
                runtime::requestCameraReconfigure,
                AndroidCameraControlLogger(),
            ).also(runtime::attachCameraControlHardware)
            runtime.attachLiveCameraStream(CameraXStreamController(source))
            LiveCameraLeaseResult.Acquired
        } catch (failure: Throwable) {
            LiveViewDiagnostics.log(LiveViewDiagnostics.WEBRTC) {
                "event=publisher_camera_acquire_failed failureType=${failure.javaClass.simpleName} " +
                    "reason=${failure.message ?: "camera_publisher_initialization_failed"}"
            }
            release()
            LiveCameraLeaseResult.Rejected(
                if (failure.message == "selected_camera_unavailable") {
                    "selected_camera_unavailable"
                } else {
                    "camera_publisher_initialization_failed"
                },
            )
        }
    }

    override suspend fun release() {
        if (!owned && frameSource == null) return
        withContext(NonCancellable) {
            rotationMonitor.stop()
            runtime.detachLiveCameraStream()
            runtime.detachCameraControlHardware(cameraControlHardware)
            cameraControlHardware = null
            val analysis = frameSource?.imageAnalysis
            val localPreview = previewUseCase
            if (analysis != null && localPreview != null) runCatching { provider?.unbind(analysis, localPreview) }
            frameSource?.close()
            surfaceSubscription?.close()
            frameSource = null
            previewUseCase = null
            provider = null
            surfaceSubscription = null
            owned = false
            runtime.cameraOwnership.release(CameraOwner.LIVE_VIEW, OWNER_TOKEN)
            releaseForeground()
        }
    }

    override fun close() {
        if (!owned) return
        rotationMonitor.stop()
        runtime.detachLiveCameraStream()
        runtime.detachCameraControlHardware(cameraControlHardware)
        cameraControlHardware = null
        frameSource?.close()
        surfaceSubscription?.close()
        provider?.unbindAll()
        runtime.cameraOwnership.release(CameraOwner.LIVE_VIEW, OWNER_TOKEN)
        owned = false
    }

    private suspend fun bind(
        source: AndroidMotionFrameSource,
        localPreview: Preview,
    ): CameraBinding = suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener(
            {
                try {
                    if (!continuation.isActive) return@addListener
                    val cameraProvider = future.get()
                    val settings = SharedPreferencesRecordingSettingsRepository(appContext).load()
                    val selector = when (settings.camera) {
                        RecordingCamera.REAR -> CameraSelector.DEFAULT_BACK_CAMERA
                        RecordingCamera.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
                    }
                    check(cameraProvider.hasCamera(selector)) { "selected_camera_unavailable" }
                    source.updateTargetRotation(rotationMonitor.currentTargetRotation())
                    LiveViewDiagnostics.log(LiveViewDiagnostics.PREVIEW) {
                        "event=bind_requested owner=live-host preview=${localPreview.identity()} " +
                            "includesPreview=true includesAnalysis=true lens=${settings.camera} " +
                            "targetRotation=${localPreview.targetRotation} lifecycle=${lifecycleOwner.lifecycle.currentState}"
                    }
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        selector,
                        UseCaseGroup.Builder()
                            .addUseCase(localPreview)
                            .addUseCase(source.imageAnalysis)
                            .build(),
                    )
                    LiveViewDiagnostics.log(LiveViewDiagnostics.ORIENTATION) {
                        "event=camera_bound owner=live-host lens=${settings.camera} " +
                            "targetRotation=${localPreview.targetRotation} " +
                            "sensorToTarget=${camera.cameraInfo.getSensorRotationDegrees(localPreview.targetRotation)}"
                    }
                    continuation.resume(CameraBinding(cameraProvider, camera))
                } catch (failure: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    private fun updateTargetRotation(rotation: Int) {
        LiveViewDiagnostics.log(LiveViewDiagnostics.ORIENTATION) {
            "event=owner_target_update owner=live-host target=$rotation preview=${previewUseCase != null} " +
                "analysis=${frameSource != null}"
        }
        previewUseCase?.targetRotation = rotation
        frameSource?.updateTargetRotation(rotation)
    }

    private fun Any?.identity(): String =
        this?.let { Integer.toHexString(System.identityHashCode(it)) } ?: "null"

    private companion object {
        const val OWNER_TOKEN = "live-view"
    }

    private data class CameraBinding(val provider: ProcessCameraProvider, val camera: Camera)
}
