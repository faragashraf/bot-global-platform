package com.ashraffarag.sentricam.motion.android

import android.util.Size
import android.util.Range
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.annotation.OptIn
import com.ashraffarag.sentricam.motion.domain.MotionFrame
import com.ashraffarag.sentricam.live.android.Yuv420FrameConverter
import com.ashraffarag.sentricam.live.android.LiveViewDiagnostics
import com.ashraffarag.sentricam.live.domain.CameraFrame
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(markerClass = [ExperimentalCamera2Interop::class])
class AndroidMotionFrameSource(
    targetWidth: Int = 1280,
    targetHeight: Int = 720,
    targetFrameRateRange: Range<Int>? = null,
    private val sampler: LumaFrameSampler = LumaFrameSampler(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, ANALYZER_THREAD_NAME)
    }
    private val closed = AtomicBoolean(false)
    private val analyzerAttached = AtomicBoolean(false)
    private val frameProcessor = CloseableFrameProcessor<ImageProxy>()
    @Volatile private var generationProvider: (() -> Long)? = null
    @Volatile private var consumer: ((MotionFrame) -> Unit)? = null
    @Volatile private var liveConsumer: ((CameraFrame) -> Unit)? = null
    @Volatile private var lastLiveFrameSignature: String? = null

    private val imageAnalysisBuilder = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(targetWidth, targetHeight),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                    ),
                )
                .build(),
        )
        .also { builder -> targetFrameRateRange?.let { range ->
            Camera2Interop.Extender(builder).setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                range,
            )
        } }

    val imageAnalysis: ImageAnalysis = imageAnalysisBuilder.build()

    fun start(generationProvider: () -> Long, consumer: (MotionFrame) -> Unit) {
        if (closed.get()) return
        this.generationProvider = generationProvider
        this.consumer = consumer
        ensureAnalyzer()
    }

    fun stop() {
        generationProvider = null
        consumer = null
        clearAnalyzerIfUnused()
    }

    @Synchronized
    fun attachLiveConsumer(consumer: (CameraFrame) -> Unit): Boolean {
        if (closed.get() || liveConsumer != null) return false
        liveConsumer = consumer
        ensureAnalyzer()
        return true
    }

    @Synchronized
    fun detachLiveConsumer() {
        liveConsumer = null
        clearAnalyzerIfUnused()
    }

    fun updateTargetRotation(rotation: Int) {
        LiveViewDiagnostics.log(LiveViewDiagnostics.ORIENTATION) {
            "event=analysis_target_rotation previous=${imageAnalysis.targetRotation} target=$rotation"
        }
        imageAnalysis.targetRotation = rotation
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
        executor.shutdown()
    }

    private fun analyze(image: ImageProxy) {
        frameProcessor.process(image) { proxy ->
            if (liveConsumer != null) {
                val signature = "${proxy.width}x${proxy.height}:${proxy.imageInfo.rotationDegrees}"
                if (signature != lastLiveFrameSignature) {
                    lastLiveFrameSignature = signature
                    LiveViewDiagnostics.log(LiveViewDiagnostics.ORIENTATION) {
                        "event=image_proxy_frame size=${proxy.width}x${proxy.height} " +
                            "crop=${proxy.cropRect.width()}x${proxy.cropRect.height()} " +
                            "rotationDegrees=${proxy.imageInfo.rotationDegrees} " +
                            "targetRotation=${imageAnalysis.targetRotation} pixelsRotated=false"
                    }
                }
            }
            liveConsumer?.let { target -> Yuv420FrameConverter.convert(proxy)?.let(target) }
            val generation = generationProvider?.invoke()
            val target = consumer
            val plane = proxy.planes.firstOrNull()
            if (generation != null && target != null && plane != null) {
                val crop = proxy.cropRect
                val sampled = sampler.sample(
                    buffer = plane.buffer,
                    imageWidth = proxy.width,
                    imageHeight = proxy.height,
                    cropLeft = crop.left,
                    cropTop = crop.top,
                    cropWidth = crop.width(),
                    cropHeight = crop.height(),
                    rowStride = plane.rowStride,
                    pixelStride = plane.pixelStride,
                )
                sampled?.let {
                    target(
                        MotionFrame(
                            luminance = it.bytes,
                            sampledWidth = it.width,
                            sampledHeight = it.height,
                            timestampMillis = nowMillis(),
                            generation = generation,
                        ),
                    )
                }
            }
        }
    }

    private fun ensureAnalyzer() {
        if (analyzerAttached.compareAndSet(false, true)) imageAnalysis.setAnalyzer(executor, ::analyze)
    }

    private fun clearAnalyzerIfUnused() {
        if (consumer == null && liveConsumer == null && analyzerAttached.compareAndSet(true, false)) {
            imageAnalysis.clearAnalyzer()
        }
    }

    private companion object {
        const val ANALYZER_THREAD_NAME = "SentriCam-MotionAnalyzer"
    }
}
