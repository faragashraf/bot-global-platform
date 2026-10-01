package com.ashraffarag.sentricam.motion.android

import com.ashraffarag.sentricam.motion.capability.DefaultMotionDetectionEngine
import com.ashraffarag.sentricam.motion.capability.MotionClock
import com.ashraffarag.sentricam.motion.capability.MotionDetectionEngine
import com.ashraffarag.sentricam.motion.capability.MotionIdFactory
import com.ashraffarag.sentricam.motion.domain.FrameDifferenceMotionAnalyzer
import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig
import com.ashraffarag.sentricam.motion.domain.MotionDetectionState
import com.ashraffarag.sentricam.motion.domain.MotionEvent
import com.ashraffarag.sentricam.motion.domain.MotionFrame
import com.ashraffarag.sentricam.motion.domain.MotionStartResult
import com.ashraffarag.sentricam.motion.domain.MotionStopResult
import com.ashraffarag.sentricam.motion.domain.MotionUpdateResult
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import com.ashraffarag.sentricam.live.android.CameraXStreamController

class CameraXMotionDetectionEngine(
    private val frameSource: AndroidMotionFrameSource = AndroidMotionFrameSource(),
) : MotionDetectionEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val delegate = DefaultMotionDetectionEngine(
        analyzer = FrameDifferenceMotionAnalyzer(),
        clock = MotionClock(System::currentTimeMillis),
        idFactory = MotionIdFactory { UUID.randomUUID().toString() },
        scheduler = CoroutineMotionScheduler(scope),
    )

    val imageAnalysis: ImageAnalysisHandle = ImageAnalysisHandle(frameSource)
    val liveStreamController = CameraXStreamController(frameSource)

    override val state: StateFlow<MotionDetectionState> = delegate.state
    override val events: Flow<MotionEvent> = delegate.events
    override val generation: Long
        get() = delegate.generation

    override suspend fun start(config: MotionDetectionConfig): MotionStartResult {
        val result = delegate.start(config)
        if (result == MotionStartResult.Started || result == MotionStartResult.AlreadyRunning) {
            frameSource.start({ delegate.generation }, delegate::submitFrame)
        }
        return result
    }

    override suspend fun stop(): MotionStopResult {
        frameSource.stop()
        return delegate.stop()
    }

    override suspend fun updateConfig(config: MotionDetectionConfig): MotionUpdateResult {
        val result = delegate.updateConfig(config)
        if (result == MotionUpdateResult.Updated) {
            if (config.enabled) frameSource.start({ delegate.generation }, delegate::submitFrame)
            else frameSource.stop()
        }
        return result
    }

    override fun submitFrame(frame: MotionFrame) = delegate.submitFrame(frame)
    override fun resetForCameraChange(): Long = delegate.resetForCameraChange()
    override fun simulateMotion(timestampMillis: Long) = delegate.simulateMotion(timestampMillis)
    override fun reportAnalyzerFailure(failure: Throwable, unsupportedCombination: Boolean) {
        frameSource.stop()
        delegate.reportAnalyzerFailure(failure, unsupportedCombination)
    }

    override suspend fun release() {
        frameSource.close()
        delegate.stop()
        scope.cancel()
    }

    class ImageAnalysisHandle internal constructor(
        private val source: AndroidMotionFrameSource,
    ) {
        val useCase: androidx.camera.core.ImageAnalysis
            get() = source.imageAnalysis

        fun updateTargetRotation(rotation: Int) = source.updateTargetRotation(rotation)
    }
}
