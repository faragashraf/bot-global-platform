package com.ashraffarag.sentricam.motion.capability

import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig
import com.ashraffarag.sentricam.motion.domain.MotionDetectionState
import com.ashraffarag.sentricam.motion.domain.MotionEvent
import com.ashraffarag.sentricam.motion.domain.MotionFrame
import com.ashraffarag.sentricam.motion.domain.MotionStartResult
import com.ashraffarag.sentricam.motion.domain.MotionStopResult
import com.ashraffarag.sentricam.motion.domain.MotionUpdateResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface MotionDetectionEngine {
    val state: StateFlow<MotionDetectionState>
    val events: Flow<MotionEvent>
    val generation: Long

    suspend fun start(config: MotionDetectionConfig): MotionStartResult
    suspend fun stop(): MotionStopResult
    suspend fun updateConfig(config: MotionDetectionConfig): MotionUpdateResult
    fun submitFrame(frame: MotionFrame)
    fun resetForCameraChange(): Long
    fun simulateMotion(timestampMillis: Long)
    fun reportAnalyzerFailure(failure: Throwable, unsupportedCombination: Boolean = false)
    suspend fun release() {
        stop()
    }
}

fun interface MotionClock {
    fun nowMillis(): Long
}

fun interface MotionIdFactory {
    fun createId(): String
}

interface MotionScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit): MotionScheduledTask
}

fun interface MotionScheduledTask {
    fun cancel()
}
