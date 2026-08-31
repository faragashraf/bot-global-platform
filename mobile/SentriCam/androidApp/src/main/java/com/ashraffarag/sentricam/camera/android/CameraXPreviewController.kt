package com.ashraffarag.sentricam.camera.android

import android.content.Context
import android.util.Log
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.ashraffarag.sentricam.camera.domain.CameraLensFacing
import com.ashraffarag.sentricam.camera.domain.CameraPreviewController

class CameraXPreviewController(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val videoCapture: VideoCapture<Recorder>,
    initialLens: CameraLensFacing = CameraLensFacing.REAR,
    private val cameraEffect: CameraEffect? = null,
    private val imageAnalysis: ImageAnalysis? = null,
    private val onAnalysisUnavailable: ((Throwable) -> Unit)? = null,
    private val onTargetRotationChanged: (() -> Unit)? = null,
    private val onCameraBound: ((Camera) -> Unit)? = null,
) : CameraPreviewController {
    private val applicationContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(applicationContext)
    private val rotationMonitor = CameraTargetRotationMonitor(
        applicationContext,
        previewView,
        ::updateTargetRotation,
    )
    private var selectedLens = initialLens
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var requestId = 0
    private var bindingInProgress = false
    private var lastTargetRotation: Int? = null

    override fun start(
        onPreviewReady: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        if (bindingInProgress) {
            onFailure(IllegalStateException("Camera binding is already in progress"))
            return
        }
        rotationMonitor.start()
        requestBinding(selectedLens, onPreviewReady, onFailure)
    }

    override fun queryAvailableLenses(
        onResult: (Set<CameraLensFacing>) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(applicationContext)
        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get()
                    val available = CameraLensFacing.entries.filterTo(mutableSetOf()) { lens ->
                        provider.hasCamera(selectorFor(lens))
                    }
                    onResult(available)
                } catch (failure: Throwable) {
                    onFailure(failure)
                }
            },
            mainExecutor,
        )
    }

    override fun switchLens(
        lens: CameraLensFacing,
        onPreviewReady: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ): Boolean {
        if (bindingInProgress || preview == null || lens == selectedLens) return false
        requestBinding(lens, onPreviewReady, onFailure)
        return true
    }

    private fun requestBinding(
        requestedLens: CameraLensFacing,
        onPreviewReady: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        bindingInProgress = true
        val currentRequestId = ++requestId
        if (!previewView.isLaidOut) {
            previewView.post {
                if (currentRequestId == requestId) {
                    bindCamera(currentRequestId, requestedLens, onPreviewReady, onFailure)
                }
            }
            return
        }
        bindCamera(currentRequestId, requestedLens, onPreviewReady, onFailure)
    }

    private fun bindCamera(
        currentRequestId: Int,
        requestedLens: CameraLensFacing,
        onPreviewReady: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(applicationContext)
        providerFuture.addListener(
            {
                if (currentRequestId != requestId) return@addListener

                try {
                    val provider = providerFuture.get()
                    val selector = selectorFor(requestedLens)
                    check(provider.hasCamera(selector)) { "The selected camera is not available" }
                    val targetRotation = rotationMonitor.currentTargetRotation()
                    val newPreview = Preview.Builder()
                        .setTargetRotation(targetRotation)
                        .build()
                        .apply { surfaceProvider = previewView.surfaceProvider }
                    videoCapture.targetRotation = targetRotation
                    preview?.let { existingPreview ->
                        imageAnalysis?.let { provider.unbind(existingPreview, videoCapture, it) }
                            ?: provider.unbind(existingPreview, videoCapture)
                    }
                    val camera = try {
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            selector,
                            createUseCaseGroup(newPreview, includeAnalysis = imageAnalysis != null),
                        )
                    } catch (analysisFailure: Throwable) {
                        if (imageAnalysis == null) throw analysisFailure
                        Log.w(TAG, "ImageAnalysis combination unavailable; preserving preview and recording", analysisFailure)
                        provider.unbind(newPreview, videoCapture, imageAnalysis)
                        onAnalysisUnavailable?.invoke(analysisFailure)
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            selector,
                            createUseCaseGroup(newPreview, includeAnalysis = false),
                        )
                    }

                    cameraProvider = provider
                    preview = newPreview
                    selectedLens = requestedLens
                    bindingInProgress = false
                    onCameraBound?.invoke(camera)
                    onPreviewReady()
                } catch (failure: Throwable) {
                    bindingInProgress = false
                    rotationMonitor.stop()
                    onFailure(failure)
                }
            },
            mainExecutor,
        )
    }

    override fun stop() {
        requestId++
        bindingInProgress = false
        rotationMonitor.stop()
        preview?.let { currentPreview ->
            imageAnalysis?.let { cameraProvider?.unbind(currentPreview, videoCapture, it) }
                ?: cameraProvider?.unbind(currentPreview, videoCapture)
        }
        preview = null
        cameraProvider = null
    }

    override fun currentOutputRotationDegrees(): Int = when (rotationMonitor.currentTargetRotation()) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun selectorFor(lens: CameraLensFacing): CameraSelector = when (lens) {
        CameraLensFacing.REAR -> CameraSelector.DEFAULT_BACK_CAMERA
        CameraLensFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
    }

    private fun updateTargetRotation(rotation: Int) {
        val changed = lastTargetRotation != null && lastTargetRotation != rotation
        lastTargetRotation = rotation
        preview?.targetRotation = rotation
        videoCapture.targetRotation = rotation
        imageAnalysis?.targetRotation = rotation
        if (changed) onTargetRotationChanged?.invoke()
    }

    private fun createUseCaseGroup(preview: Preview, includeAnalysis: Boolean): UseCaseGroup =
        UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(videoCapture)
            .apply {
                if (includeAnalysis) imageAnalysis?.let(::addUseCase)
                previewView.viewPort?.let(::setViewPort)
                cameraEffect?.let(::addEffect)
            }
            .build()

    private companion object {
        const val TAG = "CameraXPreview"
    }
}
