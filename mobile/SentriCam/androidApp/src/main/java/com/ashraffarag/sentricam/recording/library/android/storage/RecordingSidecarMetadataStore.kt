package com.ashraffarag.sentricam.recording.library.android.storage

import android.util.AtomicFile
import android.util.Log
import com.ashraffarag.sentricam.recording.engine.domain.RecordingFailureCode
import com.ashraffarag.sentricam.recording.engine.domain.RecordingLens
import com.ashraffarag.sentricam.recording.engine.domain.RecordingQuality
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSegmentMetadata
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSegmentStatus
import com.ashraffarag.sentricam.recording.engine.domain.RecordingStartReason
import com.ashraffarag.sentricam.recording.engine.domain.StopReason
import com.ashraffarag.sentricam.recording.library.domain.RecordingCamera
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object RecordingSidecarMetadataStore {
    private val writer: ExecutorService = Executors.newSingleThreadExecutor()

    fun writeAsync(
        videoPath: String,
        camera: RecordingCamera,
        timestampOverlayEnabled: Boolean,
    ) {
        writer.execute {
            writeLegacy(videoPath, camera, timestampOverlayEnabled)
        }
    }

    fun write(metadata: RecordingSegmentMetadata) {
        writeProperties(
            videoPath = metadata.absolutePath,
            values = linkedMapOf(
                KEY_VERSION to SCHEMA_VERSION.toString(),
                KEY_CAMERA to metadata.lens.toLibraryCamera().name,
                KEY_TIMESTAMP to metadata.timestampOverlayEnabled.toString(),
                KEY_SESSION_ID to metadata.sessionId,
                KEY_SEGMENT_ID to metadata.segmentId,
                KEY_SEGMENT_INDEX to metadata.segmentIndex.toString(),
                KEY_CREATED_AT to metadata.createdAtMillis.toString(),
                KEY_STARTED_AT to metadata.startedAtMillis.toString(),
                KEY_ENDED_AT to metadata.endedAtMillis.toString(),
                KEY_DURATION to metadata.durationMillis.toString(),
                KEY_FILE_SIZE to metadata.fileSizeBytes.toString(),
                KEY_WIDTH to metadata.width.toString(),
                KEY_HEIGHT to metadata.height.toString(),
                KEY_ROTATION to metadata.rotationDegrees.toString(),
                KEY_REQUESTED_QUALITY to metadata.requestedQuality.name,
                KEY_ACTUAL_QUALITY to metadata.actualQuality.name,
                KEY_AUDIO to metadata.audioEnabled.toString(),
                KEY_STATUS to metadata.status.name,
                KEY_FAILURE_CODE to (metadata.failureCode?.stableCode ?: ""),
                KEY_FINALIZED to metadata.finalized.toString(),
                KEY_TRIGGER_TYPE to metadata.triggerContext.startReason.name,
                KEY_MANUAL_CONTROL_CLAIMED to metadata.triggerContext.manualControlClaimed.toString(),
                KEY_MOTION_EVENT_ID to (metadata.triggerContext.motion?.eventId ?: ""),
                KEY_MOTION_DETECTED_AT to (metadata.triggerContext.motion?.detectedAtMillis?.toString() ?: ""),
                KEY_MOTION_RECORDING_STARTED_AT to
                    (metadata.triggerContext.motion?.recordingStartedAtMillis?.toString() ?: ""),
                KEY_LAST_MOTION_AT to (metadata.triggerContext.motion?.lastMotionAtMillis?.toString() ?: ""),
                KEY_MOTION_ENDED_AT to (metadata.triggerContext.motion?.motionEndedAtMillis?.toString() ?: ""),
                KEY_MOTION_SENSITIVITY to (metadata.triggerContext.motion?.sensitivity ?: ""),
                KEY_PEAK_MOTION_SCORE to (metadata.triggerContext.motion?.peakScore?.toString() ?: ""),
                KEY_AVERAGE_MOTION_SCORE to (metadata.triggerContext.motion?.averageScore?.toString() ?: ""),
                KEY_MOTION_BURST_COUNT to (metadata.triggerContext.motion?.burstCount?.toString() ?: ""),
                KEY_STOP_REASON to (metadata.stopReason?.name ?: ""),
            ),
        )
    }

    fun read(videoPath: String): Metadata? {
        val sidecar = sidecarFile(File(videoPath))
        if (!sidecar.isFile) return null
        return try {
            val properties = Properties().apply {
                sidecar.inputStream().buffered().use(::load)
            }
            Metadata(
                camera = properties.getProperty(KEY_CAMERA)
                    ?.let { value -> RecordingCamera.entries.firstOrNull { it.name == value } }
                    ?: RecordingCamera.UNKNOWN,
                timestampOverlayEnabled = properties.getProperty(KEY_TIMESTAMP)?.toBooleanStrictOrNull(),
                sessionId = properties.getProperty(KEY_SESSION_ID)?.takeIf(String::isNotBlank),
                segmentId = properties.getProperty(KEY_SEGMENT_ID)?.takeIf(String::isNotBlank),
                segmentIndex = properties.getProperty(KEY_SEGMENT_INDEX)?.toIntOrNull(),
                createdAtMillis = properties.getProperty(KEY_CREATED_AT)?.toLongOrNull(),
                startedAtMillis = properties.getProperty(KEY_STARTED_AT)?.toLongOrNull(),
                endedAtMillis = properties.getProperty(KEY_ENDED_AT)?.toLongOrNull(),
                durationMillis = properties.getProperty(KEY_DURATION)?.toLongOrNull(),
                fileSizeBytes = properties.getProperty(KEY_FILE_SIZE)?.toLongOrNull(),
                width = properties.getProperty(KEY_WIDTH)?.toIntOrNull(),
                height = properties.getProperty(KEY_HEIGHT)?.toIntOrNull(),
                rotationDegrees = properties.getProperty(KEY_ROTATION)?.toIntOrNull(),
                requestedQuality = properties.getProperty(KEY_REQUESTED_QUALITY)
                    ?.let { value -> RecordingQuality.entries.firstOrNull { it.name == value } },
                actualQuality = properties.getProperty(KEY_ACTUAL_QUALITY)
                    ?.let { value -> RecordingQuality.entries.firstOrNull { it.name == value } },
                audioEnabled = properties.getProperty(KEY_AUDIO)?.toBooleanStrictOrNull(),
                completionStatus = properties.getProperty(KEY_STATUS)
                    ?.let { value -> RecordingSegmentStatus.entries.firstOrNull { it.name == value } },
                failureCode = properties.getProperty(KEY_FAILURE_CODE)
                    ?.takeIf(String::isNotBlank)
                    ?.let { code -> RecordingFailureCode.entries.firstOrNull { it.stableCode == code } },
                finalized = properties.getProperty(KEY_FINALIZED)?.toBooleanStrictOrNull(),
                startReason = properties.getProperty(KEY_TRIGGER_TYPE)
                    ?.let { value -> RecordingStartReason.entries.firstOrNull { it.name == value } },
                manualControlClaimed = properties.getProperty(KEY_MANUAL_CONTROL_CLAIMED)
                    ?.toBooleanStrictOrNull(),
                motionEventId = properties.getProperty(KEY_MOTION_EVENT_ID)?.takeIf(String::isNotBlank),
                motionDetectedAtMillis = properties.getProperty(KEY_MOTION_DETECTED_AT)?.toLongOrNull(),
                motionRecordingStartedAtMillis = properties.getProperty(KEY_MOTION_RECORDING_STARTED_AT)?.toLongOrNull(),
                lastMotionAtMillis = properties.getProperty(KEY_LAST_MOTION_AT)?.toLongOrNull(),
                motionEndedAtMillis = properties.getProperty(KEY_MOTION_ENDED_AT)?.toLongOrNull(),
                motionSensitivity = properties.getProperty(KEY_MOTION_SENSITIVITY)?.takeIf(String::isNotBlank),
                peakMotionScore = properties.getProperty(KEY_PEAK_MOTION_SCORE)?.toDoubleOrNull(),
                averageMotionScore = properties.getProperty(KEY_AVERAGE_MOTION_SCORE)?.toDoubleOrNull(),
                motionBurstCount = properties.getProperty(KEY_MOTION_BURST_COUNT)?.toIntOrNull(),
                stopReason = properties.getProperty(KEY_STOP_REASON)
                    ?.let { value -> StopReason.entries.firstOrNull { it.name == value } },
            )
        } catch (failure: Throwable) {
            Log.w(TAG, "Unable to read metadata for ${sidecar.name}", failure)
            null
        }
    }

    fun awaitPendingWrites() {
        try {
            writer.submit {}.get()
        } catch (failure: Throwable) {
            Log.w(TAG, "Unable to wait for pending metadata writes", failure)
        }
    }

    fun delete(videoFile: File) {
        val sidecar = sidecarFile(videoFile)
        if (sidecar.exists() && !sidecar.delete()) {
            Log.w(TAG, "Unable to delete ${sidecar.name}")
        }
    }

    private fun writeLegacy(
        videoPath: String,
        camera: RecordingCamera,
        timestampOverlayEnabled: Boolean,
    ) {
        writeProperties(
            videoPath,
            linkedMapOf(
                KEY_VERSION to LEGACY_SCHEMA_VERSION.toString(),
                KEY_CAMERA to camera.name,
                KEY_TIMESTAMP to timestampOverlayEnabled.toString(),
            ),
        )
    }

    private fun writeProperties(videoPath: String, values: Map<String, String>) {
        val videoFile = File(videoPath)
        if (!videoFile.extension.equals("mp4", ignoreCase = true) || videoFile.parentFile?.isDirectory != true) return
        val atomicFile = AtomicFile(sidecarFile(videoFile))
        var output = try {
            atomicFile.startWrite()
        } catch (failure: Throwable) {
            Log.e(TAG, "Unable to create metadata for ${videoFile.name}", failure)
            return
        }

        try {
            val content = buildString {
                values.forEach { (key, value) ->
                    append(key).append('=').append(value).append('\n')
                }
            }.toByteArray(StandardCharsets.UTF_8)
            output.write(content)
            atomicFile.finishWrite(output)
            output = null
        } catch (failure: Throwable) {
            output?.let(atomicFile::failWrite)
            Log.e(TAG, "Unable to save metadata for ${videoFile.name}", failure)
        }
    }

    private fun sidecarFile(videoFile: File): File = File(
        videoFile.parentFile,
        videoFile.name + SIDECAR_SUFFIX,
    )

    data class Metadata(
        val camera: RecordingCamera,
        val timestampOverlayEnabled: Boolean?,
        val sessionId: String? = null,
        val segmentId: String? = null,
        val segmentIndex: Int? = null,
        val createdAtMillis: Long? = null,
        val startedAtMillis: Long? = null,
        val endedAtMillis: Long? = null,
        val durationMillis: Long? = null,
        val fileSizeBytes: Long? = null,
        val width: Int? = null,
        val height: Int? = null,
        val rotationDegrees: Int? = null,
        val requestedQuality: RecordingQuality? = null,
        val actualQuality: RecordingQuality? = null,
        val audioEnabled: Boolean? = null,
        val completionStatus: RecordingSegmentStatus? = null,
        val failureCode: RecordingFailureCode? = null,
        val finalized: Boolean? = null,
        val startReason: RecordingStartReason? = null,
        val manualControlClaimed: Boolean? = null,
        val motionEventId: String? = null,
        val motionDetectedAtMillis: Long? = null,
        val motionRecordingStartedAtMillis: Long? = null,
        val lastMotionAtMillis: Long? = null,
        val motionEndedAtMillis: Long? = null,
        val motionSensitivity: String? = null,
        val peakMotionScore: Double? = null,
        val averageMotionScore: Double? = null,
        val motionBurstCount: Int? = null,
        val stopReason: StopReason? = null,
    )

    private fun RecordingLens.toLibraryCamera(): RecordingCamera = when (this) {
        RecordingLens.BACK -> RecordingCamera.REAR
        RecordingLens.FRONT -> RecordingCamera.FRONT
    }

    private const val TAG = "RecordingSidecar"
    private const val SIDECAR_SUFFIX = ".metadata"
    private const val KEY_VERSION = "version"
    private const val KEY_CAMERA = "camera"
    private const val KEY_TIMESTAMP = "timestampOverlayEnabled"
    private const val KEY_SESSION_ID = "sessionId"
    private const val KEY_SEGMENT_ID = "segmentId"
    private const val KEY_SEGMENT_INDEX = "segmentIndex"
    private const val KEY_CREATED_AT = "createdAtMillis"
    private const val KEY_STARTED_AT = "startedAtMillis"
    private const val KEY_ENDED_AT = "endedAtMillis"
    private const val KEY_DURATION = "durationMillis"
    private const val KEY_FILE_SIZE = "fileSizeBytes"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"
    private const val KEY_ROTATION = "rotationDegrees"
    private const val KEY_REQUESTED_QUALITY = "requestedQuality"
    private const val KEY_ACTUAL_QUALITY = "actualQuality"
    private const val KEY_AUDIO = "audioEnabled"
    private const val KEY_STATUS = "completionStatus"
    private const val KEY_FAILURE_CODE = "failureCode"
    private const val KEY_FINALIZED = "finalized"
    private const val KEY_TRIGGER_TYPE = "triggerType"
    private const val KEY_MANUAL_CONTROL_CLAIMED = "manualControlClaimed"
    private const val KEY_MOTION_EVENT_ID = "motionEventId"
    private const val KEY_MOTION_DETECTED_AT = "motionDetectedAtMillis"
    private const val KEY_MOTION_RECORDING_STARTED_AT = "motionRecordingStartedAtMillis"
    private const val KEY_LAST_MOTION_AT = "lastMotionAtMillis"
    private const val KEY_MOTION_ENDED_AT = "motionEndedAtMillis"
    private const val KEY_MOTION_SENSITIVITY = "motionSensitivity"
    private const val KEY_PEAK_MOTION_SCORE = "peakMotionScore"
    private const val KEY_AVERAGE_MOTION_SCORE = "averageMotionScore"
    private const val KEY_MOTION_BURST_COUNT = "motionBurstCount"
    private const val KEY_STOP_REASON = "stopReason"
    private const val LEGACY_SCHEMA_VERSION = 1
    private const val SCHEMA_VERSION = 3
}
