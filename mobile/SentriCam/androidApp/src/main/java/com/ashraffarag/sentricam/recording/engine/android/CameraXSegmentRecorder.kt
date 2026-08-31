package com.ashraffarag.sentricam.recording.engine.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.CameraInfo
import androidx.camera.core.DynamicRange
import androidx.camera.core.MirrorMode
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.ashraffarag.sentricam.recording.engine.capability.RecordingQualityProvider
import com.ashraffarag.sentricam.recording.engine.capability.SegmentFinalizeResult
import com.ashraffarag.sentricam.recording.engine.capability.SegmentRecorder
import com.ashraffarag.sentricam.recording.engine.capability.SegmentRecorderListener
import com.ashraffarag.sentricam.recording.engine.capability.SegmentRecordingHandle
import com.ashraffarag.sentricam.recording.engine.capability.SegmentStartRequest
import com.ashraffarag.sentricam.recording.engine.domain.RecordingFailure
import com.ashraffarag.sentricam.recording.engine.domain.RecordingFailureCode
import com.ashraffarag.sentricam.recording.engine.domain.RecordingProfileId
import com.ashraffarag.sentricam.recording.engine.domain.RecordingQuality
import com.ashraffarag.sentricam.recording.engine.domain.RecoveryAction
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraXSegmentRecorder(
    context: Context,
    profileId: RecordingProfileId,
    private val videoMetadataProbe: AndroidVideoMetadataProbe = AndroidVideoMetadataProbe(),
) : SegmentRecorder, RecordingQualityProvider {
    private val applicationContext = context.applicationContext
    private val eventExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, EVENT_THREAD_NAME)
    }
    private val orderedQualities = cameraXQualities(profileId)
    private val recorder = Recorder.Builder()
        .setQualitySelector(QualitySelector.fromOrderedList(orderedQualities))
        .build()

    // Preview mirroring is independent; encoded camera and overlay layers remain unmirrored.
    val videoCapture: VideoCapture<Recorder> = VideoCapture.Builder(recorder)
        .setMirrorMode(MirrorMode.MIRROR_MODE_OFF)
        .build()

    @Volatile
    private var availableQualities: Set<RecordingQuality> = orderedQualities
        .mapTo(linkedSetOf(), ::domainQuality)

    fun onCameraBound(cameraInfo: CameraInfo) {
        val discovered = try {
            Recorder.getVideoCapabilities(cameraInfo)
                .getSupportedQualities(DynamicRange.SDR)
                .mapTo(linkedSetOf(), ::domainQuality)
        } catch (failure: Throwable) {
            Log.w(TAG, "Unable to query CameraX video qualities; retaining ordered fallback", failure)
            emptySet()
        }
        if (discovered.isNotEmpty()) availableQualities = discovered
    }

    override fun supportedQualities(): Set<RecordingQuality> = availableQualities

    override fun start(
        request: SegmentStartRequest,
        listener: SegmentRecorderListener,
    ): SegmentRecordingHandle {
        val outputBuilder = FileOutputOptions.Builder(File(request.absolutePath))
        request.maximumFileSizeBytes?.let(outputBuilder::setFileSizeLimit)
        val pending = recorder.prepareRecording(applicationContext, outputBuilder.build())
        val configured = if (request.audioEnabled) {
            if (ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.RECORD_AUDIO,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("Audio permission is unavailable")
            }
            pending.withAudioEnabled()
        } else {
            pending
        }
        val recording = configured.start(eventExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Start -> listener.onStarted(request.sessionId, request.segmentId)
                is VideoRecordEvent.Finalize -> listener.onFinalized(
                    request.sessionId,
                    request.segmentId,
                    event.toDomainResult(request),
                )
            }
        }
        return CameraXSegmentRecordingHandle(recording)
    }

    override fun release() {
        eventExecutor.shutdown()
    }

    private fun VideoRecordEvent.Finalize.toDomainResult(
        request: SegmentStartRequest,
    ): SegmentFinalizeResult {
        val fallbackDuration = recordingStats.recordedDurationNanos / NANOS_PER_MILLISECOND
        val output = try {
            videoMetadataProbe.probe(request.absolutePath, fallbackDuration, request.audioEnabled)
        } catch (failure: Throwable) {
            Log.w(TAG, "Unable to probe finalized segment ${request.segmentIndex}", failure)
            com.ashraffarag.sentricam.recording.engine.capability.FinalizedSegmentOutput(
                durationMillis = fallbackDuration.coerceAtLeast(0L),
                fileSizeBytes = File(request.absolutePath).length().coerceAtLeast(0L),
                width = 0,
                height = 0,
                rotationDegrees = 0,
                actualQuality = availableQualities.firstOrNull() ?: RecordingQuality.SD,
                audioEnabled = recordingStats.audioStats.hasAudio(),
                finalized = error == VideoRecordEvent.Finalize.ERROR_NONE,
            )
        }

        if (error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED ||
            error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
        ) {
            return SegmentFinalizeResult.Success(
                output = output.copy(finalized = true),
                continueSession = true,
            )
        }

        if (error == VideoRecordEvent.Finalize.ERROR_NONE) {
            if (request.audioEnabled && recordingStats.audioStats.hasError()) {
                return SegmentFinalizeResult.Failure(
                    failure = RecordingFailure(
                        code = RecordingFailureCode.RECORDING_START_FAILED,
                        retryAllowed = true,
                        recoveryAction = RecoveryAction.RETRY,
                        diagnosticTag = "audio_${recordingStats.audioStats.audioState}",
                        technicalCause = recordingStats.audioStats.errorCause,
                    ),
                    partialOutput = output,
                )
            }
            return SegmentFinalizeResult.Success(output)
        }

        val failure = when (error) {
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> RecordingFailure(
                RecordingFailureCode.INSUFFICIENT_STORAGE,
                retryAllowed = true,
                recoveryAction = RecoveryAction.FREE_STORAGE,
                diagnosticTag = "camerax_$error",
                technicalCause = cause,
            )
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> RecordingFailure(
                RecordingFailureCode.CAMERA_UNAVAILABLE,
                retryAllowed = true,
                recoveryAction = RecoveryAction.RESTART_CAMERA,
                diagnosticTag = "camerax_$error",
                technicalCause = cause,
            )
            else -> RecordingFailure(
                RecordingFailureCode.SEGMENT_FINALIZATION_FAILED,
                retryAllowed = true,
                recoveryAction = RecoveryAction.RETRY,
                diagnosticTag = "camerax_$error",
                technicalCause = cause,
            )
        }
        Log.e(TAG, "CameraX segment finalization failed with error $error", cause)
        return SegmentFinalizeResult.Failure(failure, output.copy(finalized = false))
    }

    private fun cameraXQualities(profileId: RecordingProfileId): List<Quality> = when (profileId) {
        RecordingProfileId.LOW -> listOf(Quality.SD, Quality.HD)
        RecordingProfileId.STANDARD -> listOf(Quality.HD, Quality.SD)
        RecordingProfileId.HIGH -> listOf(Quality.FHD, Quality.HD, Quality.SD)
    }

    private fun domainQuality(quality: Quality): RecordingQuality = when (quality) {
        Quality.FHD, Quality.UHD -> RecordingQuality.FULL_HD
        Quality.HD -> RecordingQuality.HD
        else -> RecordingQuality.SD
    }

    private class CameraXSegmentRecordingHandle(
        private val recording: Recording,
    ) : SegmentRecordingHandle {
        override fun stop() = recording.stop()
        override fun pause() = recording.pause()
        override fun resume() = recording.resume()
    }

    private companion object {
        const val TAG = "CameraXSegmentRecorder"
        const val EVENT_THREAD_NAME = "SentriCam-RecordingEvents"
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
