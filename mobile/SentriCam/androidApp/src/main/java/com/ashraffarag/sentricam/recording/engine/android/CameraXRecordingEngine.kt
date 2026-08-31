package com.ashraffarag.sentricam.recording.engine.android

import android.content.Context
import androidx.camera.core.CameraInfo
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import com.ashraffarag.sentricam.recording.engine.capability.DefaultRecordingEngine
import com.ashraffarag.sentricam.recording.engine.capability.RecordingEngine
import com.ashraffarag.sentricam.recording.engine.capability.RecordingIdFactory
import com.ashraffarag.sentricam.recording.engine.domain.DefaultRecordingFileNameFactory
import com.ashraffarag.sentricam.recording.engine.domain.PauseResult
import com.ashraffarag.sentricam.recording.engine.domain.PrepareResult
import com.ashraffarag.sentricam.recording.engine.domain.RecordingProfileId
import com.ashraffarag.sentricam.recording.engine.domain.RecordingRequest
import com.ashraffarag.sentricam.recording.engine.domain.RecordingState
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSegmentMetadata
import com.ashraffarag.sentricam.recording.engine.domain.RecordingTriggerContext
import com.ashraffarag.sentricam.recording.engine.domain.ResumeResult
import com.ashraffarag.sentricam.recording.engine.domain.StartResult
import com.ashraffarag.sentricam.recording.engine.domain.StopReason
import com.ashraffarag.sentricam.recording.engine.domain.StopResult
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

class CameraXRecordingEngine(
    context: Context,
    profileId: RecordingProfileId,
    simulateStorageWarning: Boolean = false,
    onFinalizedSegment: (RecordingSegmentMetadata) -> Unit = {},
) : RecordingEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cameraXRecorder = CameraXSegmentRecorder(context, profileId)
    private val delegate = DefaultRecordingEngine(
        recorder = cameraXRecorder,
        storage = AndroidRecordingStorageGateway(context, simulateStorageWarning),
        qualityProvider = cameraXRecorder,
        metadataStore = AndroidRecordingMetadataStore(onFinalizedSegment),
        fileNameFactory = DefaultRecordingFileNameFactory(),
        clock = com.ashraffarag.sentricam.recording.engine.capability.RecordingClock(
            System::currentTimeMillis,
        ),
        idFactory = RecordingIdFactory { UUID.randomUUID().toString() },
        scheduler = CoroutineRecordingScheduler(scope),
        scope = scope,
    )

    val videoCapture: VideoCapture<Recorder>
        get() = cameraXRecorder.videoCapture

    fun onCameraBound(cameraInfo: CameraInfo) {
        cameraXRecorder.onCameraBound(cameraInfo)
    }

    override val state: StateFlow<RecordingState>
        get() = delegate.state

    override suspend fun prepare(request: RecordingRequest): PrepareResult = delegate.prepare(request)
    override suspend fun start(): StartResult = delegate.start()
    override suspend fun stop(reason: StopReason): StopResult = delegate.stop(reason)
    override suspend fun pause(): PauseResult = delegate.pause()
    override suspend fun resume(): ResumeResult = delegate.resume()
    override suspend fun updateTriggerContext(context: RecordingTriggerContext): Boolean =
        delegate.updateTriggerContext(context)
    override suspend fun release() = delegate.release()
}
