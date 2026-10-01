package com.ashraffarag.sentricam.monitoring.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.CameraEffect
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.ashraffarag.sentricam.BuildConfig
import com.ashraffarag.sentricam.camera.android.CameraTargetRotationMonitor
import com.ashraffarag.sentricam.device.android.DeviceRuntime
import com.ashraffarag.sentricam.device.domain.CameraState
import com.ashraffarag.sentricam.device.domain.DeviceCameraLens
import com.ashraffarag.sentricam.device.domain.DeviceCameraState
import com.ashraffarag.sentricam.device.domain.RecordingOrigin
import com.ashraffarag.sentricam.device.integration.AppRecordingSettingsCommandPort
import com.ashraffarag.sentricam.device.integration.MotionEngineCommandPort
import com.ashraffarag.sentricam.device.integration.RecordingEngineCommandPort
import com.ashraffarag.sentricam.monitoring.domain.CameraOwner
import com.ashraffarag.sentricam.monitoring.domain.CameraOwnershipResult
import com.ashraffarag.sentricam.live.android.LiveViewDiagnostics
import com.ashraffarag.sentricam.motion.android.CameraXMotionDetectionEngine
import com.ashraffarag.sentricam.motion.capability.MotionRecordingCoordinator
import com.ashraffarag.sentricam.motion.capability.MotionRecordingRequestProvider
import com.ashraffarag.sentricam.recording.engine.android.CameraXRecordingEngine
import com.ashraffarag.sentricam.recording.engine.android.SharedPreferencesRecordingEngineSettingsRepository
import com.ashraffarag.sentricam.recording.engine.domain.RecordingLens
import com.ashraffarag.sentricam.recording.engine.domain.RecordingRequest
import com.ashraffarag.sentricam.recording.engine.domain.RecordingState
import com.ashraffarag.sentricam.recording.engine.domain.StopReason
import com.ashraffarag.sentricam.recording.overlay.android.CameraXRecordingOverlayEngine
import com.ashraffarag.sentricam.recording.settings.android.SharedPreferencesRecordingSettingsRepository
import com.ashraffarag.sentricam.recording.settings.domain.RecordingCamera
import com.ashraffarag.sentricam.cameracontrol.android.AndroidCameraXControl
import com.ashraffarag.sentricam.cameracontrol.android.AndroidCameraControlLogger
import com.ashraffarag.sentricam.cameracontrol.android.AndroidCameraFrameRateResolver
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlValues
import com.ashraffarag.sentricam.motion.android.AndroidMotionFrameSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** CameraX/engine runtime owned exclusively by the foreground monitoring service. */
class MonitoringCameraSession(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val deviceRuntime: DeviceRuntime,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val cameraSettings = SharedPreferencesRecordingSettingsRepository(appContext)
    private val engineSettings = SharedPreferencesRecordingEngineSettingsRepository(appContext, BuildConfig.DEBUG)
    private val motionSettings = deviceRuntime.motionSettings
    private var provider: ProcessCameraProvider? = null
    private var analysisBound = false
    private var recordingEngine: CameraXRecordingEngine? = null
    private var motionEngine: CameraXMotionDetectionEngine? = null
    private var previewUseCase: Preview? = null
    private var previewSurfaceSubscription: AutoCloseable? = null
    private var coordinator: MotionRecordingCoordinator? = null
    private var overlayEngine: CameraXRecordingOverlayEngine? = null
    private var analysisFailure: Throwable? = null
    private var cameraControlHardware: AndroidCameraXControl? = null
    private var started = false
    private val rotationMonitor = CameraTargetRotationMonitor(appContext, ::updateTargetRotation)

    suspend fun start() {
        if (started) return
        val initialOwnership = deviceRuntime.cameraOwnership.acquire(CameraOwner.MONITORING_SERVICE, OWNER_TOKEN)
        val ownership = if (initialOwnership is CameraOwnershipResult.Rejected) {
            val released = withTimeoutOrNull(CAMERA_HANDOFF_TIMEOUT_MILLIS) {
                deviceRuntime.cameraOwnership.owner.first { it == CameraOwner.NONE }
            }
            if (released == null) initialOwnership else {
                deviceRuntime.cameraOwnership.acquire(CameraOwner.MONITORING_SERVICE, OWNER_TOKEN)
            }
        } else {
            initialOwnership
        }
        when (ownership) {
            CameraOwnershipResult.Acquired,
            CameraOwnershipResult.AlreadyOwned,
            -> Unit
            is CameraOwnershipResult.Rejected -> error("camera_owned_by_${ownership.currentOwner.name.lowercase()}")
        }
        try {
            deviceRuntime.publishCameraState(CameraState(state = DeviceCameraState.STARTING))
            val camera = cameraSettings.load()
            val control = deviceRuntime.cameraControlSettings()
            val effectiveFramesPerSecond = control.effectiveFramesPerSecond()
            val engine = engineSettings.load()
            val motion = motionSettings.load()
            val recorder = CameraXRecordingEngine(
                appContext,
                engine.profileId,
                BuildConfig.DEBUG && engine.simulateStorageWarning,
                deviceRuntime::recordingSegmentFinalized,
            )
            val dimensions = control.resolution.toDimensions()
            val detector = CameraXMotionDetectionEngine(
                AndroidMotionFrameSource(
                    targetWidth = dimensions.first,
                    targetHeight = dimensions.second,
                    targetFrameRateRange = AndroidCameraFrameRateResolver(appContext).resolve(
                        control.lens,
                        effectiveFramesPerSecond,
                    ),
                ),
            )
            val overlayConfiguration = control.dateTimeOverlay
            val overlay = overlayConfiguration.takeIf { it.showDateTime }?.let(::CameraXRecordingOverlayEngine)
            recordingEngine = recorder
            motionEngine = detector
            deviceRuntime.attachLiveCameraStream(detector.liveStreamController)
            overlayEngine = overlay

            rotationMonitor.start()
            val localPreview = Preview.Builder()
                .setTargetRotation(rotationMonitor.currentTargetRotation())
                .build()
            LiveViewDiagnostics.log(LiveViewDiagnostics.PREVIEW) {
                "event=use_case_created owner=monitoring targetRotation=${localPreview.targetRotation} " +
                    "instance=${localPreview.identity()}"
            }
            previewUseCase = localPreview
            previewSurfaceSubscription = deviceRuntime.livePreview.observeSurfaceProvider { surface ->
                ContextCompat.getMainExecutor(appContext).execute {
                    LiveViewDiagnostics.log(LiveViewDiagnostics.PREVIEW) {
                        "event=surface_assigned owner=monitoring preview=${localPreview.identity()} " +
                            "provider=${surface.identity()} bound=${provider != null}"
                    }
                    localPreview.surfaceProvider = surface
                }
            }
            val cameraBinding = bind(
                recorder,
                detector,
                localPreview,
                overlay?.cameraEffect,
                camera.camera,
            )
            provider = cameraBinding.provider
            cameraControlHardware = AndroidCameraXControl(
                cameraBinding.camera,
                deviceRuntime.livePreview,
                deviceRuntime::requestCameraReconfigure,
                AndroidCameraControlLogger(),
            ).also(deviceRuntime::attachCameraControlHardware)
            val requestProvider = {
                RecordingRequest(
                    profile = engine.toProfile(camera.audioEnabled),
                    lens = camera.camera.toRecordingLens(),
                    orientationDegrees = 0,
                    audioPermissionGranted = camera.audioEnabled && hasPermission(Manifest.permission.RECORD_AUDIO),
                    timestampOverlayEnabled = overlayConfiguration.showDateTime,
                )
            }
            val motionCoordinator = MotionRecordingCoordinator(
                detector,
                recorder,
                MotionRecordingRequestProvider { requestProvider() },
                System::currentTimeMillis,
                scope,
            ).also(MotionRecordingCoordinator::start)
            coordinator = motionCoordinator
            deviceRuntime.attachRecordingState(recorder.state)
            deviceRuntime.attachMotionState(detector.state, motion)
            deviceRuntime.attachCommandPorts(
                RecordingEngineCommandPort(
                    engine = recorder,
                    requestProvider = { _: RecordingOrigin -> requestProvider() },
                    manualStarter = motionCoordinator::startManual,
                ),
                MotionEngineCommandPort(detector, motionSettings, deviceRuntime::publishMotionConfiguration),
                AppRecordingSettingsCommandPort(engineSettings, cameraSettings),
            )
            if (motion.enabled) {
                analysisFailure?.let { detector.reportAnalyzerFailure(it, unsupportedCombination = true) }
                    ?: detector.start(motion)
            }
            val lensCount = listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)
                .count(cameraBinding.provider::hasCamera)
            deviceRuntime.publishCameraState(
                CameraState(DeviceCameraState.READY, camera.camera.toDeviceLens(), lensCount),
            )
            started = true
        } catch (failure: Throwable) {
            stop()
            throw failure
        }
    }

    suspend fun stop() {
        withContext(NonCancellable) {
            rotationMonitor.stop()
            val recorder = recordingEngine
            val detector = motionEngine
            val localPreview = previewUseCase
            coordinator?.release()
            coordinator = null
            safely("stop motion detection") { detector?.stop() }
            if (recorder?.state?.value?.isActive() == true) {
                safely("stop recording") { recorder.stop(StopReason.LIFECYCLE) }
                withTimeoutOrNull(RECORDING_FINALIZATION_TIMEOUT_MILLIS) {
                    recorder.state.first { !it.isActive() }
                }
            }
            provider?.let { cameraProvider ->
                val video = recorder?.videoCapture
                val analysis = detector?.imageAnalysis?.useCase
                safely("unbind camera use cases") {
                    when {
                        localPreview != null && video != null && analysis != null && analysisBound ->
                            cameraProvider.unbind(localPreview, video, analysis)
                        localPreview != null && video != null -> cameraProvider.unbind(localPreview, video)
                        video != null && analysis != null && analysisBound -> cameraProvider.unbind(video, analysis)
                        video != null -> cameraProvider.unbind(video)
                    }
                }
            }
            safely("detach preview surface") { localPreview?.surfaceProvider = null }
            safely("release preview surface subscription") { previewSurfaceSubscription?.close() }
            previewSurfaceSubscription = null
            previewUseCase = null
            provider = null
            analysisBound = false
            analysisFailure = null
            safely("release motion engine") { detector?.release() }
            deviceRuntime.detachCameraControlHardware(cameraControlHardware)
            cameraControlHardware = null
            deviceRuntime.detachLiveCameraStream()
            safely("release recording engine") { recorder?.release() }
            safely("release overlay engine") { overlayEngine?.close() }
            overlayEngine = null
            motionEngine = null
            recordingEngine = null
            if (deviceRuntime.cameraOwnership.owner.value == CameraOwner.MONITORING_SERVICE) {
                deviceRuntime.detachCommandPorts()
                deviceRuntime.detachMotionState()
                deviceRuntime.detachRecordingState()
                deviceRuntime.publishCameraState(CameraState())
                deviceRuntime.cameraOwnership.release(CameraOwner.MONITORING_SERVICE, OWNER_TOKEN)
            }
            started = false
        }
    }

    private suspend fun safely(operation: String, block: suspend () -> Unit) {
        runCatching { block() }.onFailure { failure ->
            Log.w(TAG, "Unable to $operation while closing monitoring session", failure)
        }
    }

    private suspend fun bind(
        recorder: CameraXRecordingEngine,
        detector: CameraXMotionDetectionEngine,
        localPreview: Preview,
        effect: CameraEffect?,
        camera: RecordingCamera,
    ): CameraBinding = suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener(
            {
                try {
                    if (!continuation.isActive) return@addListener
                    val cameraProvider = future.get()
                    val selector = camera.toSelector()
                    check(cameraProvider.hasCamera(selector)) { "selected_camera_unavailable" }
                    val targetRotation = rotationMonitor.currentTargetRotation()
                    localPreview.targetRotation = targetRotation
                    recorder.videoCapture.targetRotation = targetRotation
                    detector.imageAnalysis.updateTargetRotation(targetRotation)
                    val group = UseCaseGroup.Builder()
                        .addUseCase(localPreview)
                        .addUseCase(recorder.videoCapture)
                        .addUseCase(detector.imageAnalysis.useCase)
                        .apply { effect?.let(::addEffect) }
                        .build()
                    LiveViewDiagnostics.log(LiveViewDiagnostics.PREVIEW) {
                        "event=bind_requested owner=monitoring preview=${localPreview.identity()} " +
                            "includesPreview=true includesVideo=true includesAnalysis=true lens=$camera " +
                            "targetRotation=$targetRotation lifecycle=${lifecycleOwner.lifecycle.currentState}"
                    }
                    val bound = try {
                        analysisBound = true
                        cameraProvider.bindToLifecycle(lifecycleOwner, selector, group)
                    } catch (analysisFailure: Throwable) {
                        LiveViewDiagnostics.warn(LiveViewDiagnostics.PREVIEW, analysisFailure) {
                            "event=bind_fallback owner=monitoring includesPreview=true includesVideo=true includesAnalysis=false"
                        }
                        analysisBound = false
                        this@MonitoringCameraSession.analysisFailure = analysisFailure
                        cameraProvider.unbind(localPreview, recorder.videoCapture, detector.imageAnalysis.useCase)
                        val previewAndRecording = UseCaseGroup.Builder()
                            .addUseCase(localPreview)
                            .addUseCase(recorder.videoCapture)
                            .apply { effect?.let(::addEffect) }
                            .build()
                        cameraProvider.bindToLifecycle(lifecycleOwner, selector, previewAndRecording)
                    }
                    LiveViewDiagnostics.log(LiveViewDiagnostics.ORIENTATION) {
                        "event=camera_bound owner=monitoring lens=$camera targetRotation=$targetRotation " +
                            "sensorToTarget=${bound.cameraInfo.getSensorRotationDegrees(targetRotation)} " +
                            "includesPreview=true includesVideo=true includesAnalysis=$analysisBound"
                    }
                    recorder.onCameraBound(bound.cameraInfo)
                    if (continuation.isActive) continuation.resume(CameraBinding(cameraProvider, bound))
                } catch (failure: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun updateTargetRotation(rotation: Int) {
        LiveViewDiagnostics.log(LiveViewDiagnostics.ORIENTATION) {
            "event=owner_target_update owner=monitoring target=$rotation preview=${previewUseCase != null} " +
                "video=${recordingEngine != null} analysis=${motionEngine != null}"
        }
        previewUseCase?.targetRotation = rotation
        recordingEngine?.videoCapture?.targetRotation = rotation
        motionEngine?.imageAnalysis?.updateTargetRotation(rotation)
    }

    private fun RecordingCamera.toSelector() = when (this) {
        RecordingCamera.REAR -> CameraSelector.DEFAULT_BACK_CAMERA
        RecordingCamera.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
    }

    private fun RecordingCamera.toRecordingLens() = when (this) {
        RecordingCamera.REAR -> RecordingLens.BACK
        RecordingCamera.FRONT -> RecordingLens.FRONT
    }

    private fun RecordingCamera.toDeviceLens() = when (this) {
        RecordingCamera.REAR -> DeviceCameraLens.BACK
        RecordingCamera.FRONT -> DeviceCameraLens.FRONT
    }

    private fun String.toDimensions(): Pair<Int, Int> {
        val parts = split('x', limit = 2)
        val width = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(320, 3840) ?: 1280
        val height = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(240, 2160) ?: 720
        return width to height
    }

    private fun com.ashraffarag.sentricam.cameracontrol.domain.CameraControlSettings.effectiveFramesPerSecond() =
        when (nightProfile) {
            CameraControlValues.NIGHT -> minOf(framesPerSecond, 15)
            CameraControlValues.INDOOR -> minOf(framesPerSecond, 24)
            else -> framesPerSecond
        }.coerceIn(1, 60)

    private fun RecordingState.isActive() = when (this) {
        is RecordingState.Starting,
        is RecordingState.Recording,
        is RecordingState.RotatingSegment,
        is RecordingState.Stopping,
        -> true
        else -> false
    }

    private fun Any?.identity(): String =
        this?.let { Integer.toHexString(System.identityHashCode(it)) } ?: "null"

    private companion object {
        const val RECORDING_FINALIZATION_TIMEOUT_MILLIS = 10_000L
        const val CAMERA_HANDOFF_TIMEOUT_MILLIS = 5_000L
        const val OWNER_TOKEN = "monitoring-service"
        const val TAG = "MonitoringCamera"
    }

    private data class CameraBinding(val provider: ProcessCameraProvider, val camera: Camera)
}
