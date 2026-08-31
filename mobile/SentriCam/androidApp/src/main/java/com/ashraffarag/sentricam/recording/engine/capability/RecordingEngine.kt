package com.ashraffarag.sentricam.recording.engine.capability

import com.ashraffarag.sentricam.recording.engine.domain.PauseResult
import com.ashraffarag.sentricam.recording.engine.domain.PrepareResult
import com.ashraffarag.sentricam.recording.engine.domain.RecordingRequest
import com.ashraffarag.sentricam.recording.engine.domain.RecordingState
import com.ashraffarag.sentricam.recording.engine.domain.RecordingTriggerContext
import com.ashraffarag.sentricam.recording.engine.domain.ResumeResult
import com.ashraffarag.sentricam.recording.engine.domain.StartResult
import com.ashraffarag.sentricam.recording.engine.domain.StopReason
import com.ashraffarag.sentricam.recording.engine.domain.StopResult
import kotlinx.coroutines.flow.StateFlow

interface RecordingEngine {
    val state: StateFlow<RecordingState>

    suspend fun prepare(request: RecordingRequest): PrepareResult

    suspend fun start(): StartResult

    suspend fun stop(reason: StopReason = StopReason.USER): StopResult

    suspend fun pause(): PauseResult

    suspend fun resume(): ResumeResult

    suspend fun updateTriggerContext(context: RecordingTriggerContext): Boolean

    suspend fun release()
}
