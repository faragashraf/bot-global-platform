package com.ashraffarag.sentricam.recording.engine.android

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.ashraffarag.sentricam.recording.engine.capability.RecordingStorageCheck
import com.ashraffarag.sentricam.recording.engine.capability.RecordingStorageGateway
import com.ashraffarag.sentricam.recording.engine.capability.RecordingTargetResult
import com.ashraffarag.sentricam.recording.engine.domain.RecordingFailure
import com.ashraffarag.sentricam.recording.engine.domain.RecordingFailureCode
import com.ashraffarag.sentricam.recording.engine.domain.RecordingStorageLevel
import com.ashraffarag.sentricam.recording.engine.domain.RecordingStoragePolicy
import com.ashraffarag.sentricam.recording.engine.domain.RecoveryAction
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidRecordingStorageGateway(
    context: Context,
    private val simulateWarning: Boolean = false,
) : RecordingStorageGateway {
    private val applicationContext = context.applicationContext

    override suspend fun validateForStart(
        policy: RecordingStoragePolicy,
    ): RecordingStorageCheck = withContext(Dispatchers.IO) {
        val directory = recordingDirectory() ?: return@withContext unavailable("directory")
        if (Environment.getExternalStorageState(directory) != Environment.MEDIA_MOUNTED) {
            return@withContext unavailable("state")
        }
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory || !directory.canWrite()) {
            return@withContext unavailable("writable_directory")
        }
        try {
            val probe = File.createTempFile(WRITE_PROBE_PREFIX, WRITE_PROBE_SUFFIX, directory)
            if (!probe.delete()) return@withContext unavailable("write_probe_cleanup")
        } catch (failure: Throwable) {
            return@withContext unavailable("write_probe", failure)
        }
        statusFor(directory, policy)
    }

    override suspend fun createTarget(fileName: String): RecordingTargetResult = withContext(Dispatchers.IO) {
        val directory = recordingDirectory()
            ?: return@withContext RecordingTargetResult.Failed(storageUnavailable("directory"))
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory || !directory.canWrite()) {
            return@withContext RecordingTargetResult.Failed(storageUnavailable("target_directory"))
        }

        val requested = File(directory, File(fileName).name)
        val baseName = requested.nameWithoutExtension
        val extension = requested.extension.ifBlank { MP4_EXTENSION }
        var candidate = requested
        var collisionIndex = 1
        while (candidate.exists()) {
            candidate = File(directory, "$baseName-$collisionIndex.$extension")
            collisionIndex++
        }
        RecordingTargetResult.Created(candidate.absolutePath)
    }

    override suspend fun currentStatus(
        policy: RecordingStoragePolicy,
    ): RecordingStorageCheck = withContext(Dispatchers.IO) {
        val directory = recordingDirectory() ?: return@withContext unavailable("monitor_directory")
        if (!directory.isDirectory || Environment.getExternalStorageState(directory) != Environment.MEDIA_MOUNTED) {
            return@withContext unavailable("monitor_state")
        }
        statusFor(directory, policy)
    }

    private fun statusFor(directory: File, policy: RecordingStoragePolicy): RecordingStorageCheck = try {
        val available = StatFs(directory.absolutePath).availableBytes.coerceAtLeast(0L)
        val level = when {
            simulateWarning -> RecordingStorageLevel.WARNING
            available <= policy.criticalBytes -> RecordingStorageLevel.CRITICAL
            available <= policy.warningBytes -> RecordingStorageLevel.WARNING
            else -> RecordingStorageLevel.HEALTHY
        }
        RecordingStorageCheck.Available(
            availableBytes = available,
            level = level,
            mustStop = available <= policy.stopBytes,
        )
    } catch (failure: Throwable) {
        unavailable("stat_fs", failure)
    }

    private fun recordingDirectory(): File? =
        applicationContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?.let { File(it, RECORDINGS_DIRECTORY) }

    private fun unavailable(tag: String, cause: Throwable? = null) =
        RecordingStorageCheck.Unavailable(storageUnavailable(tag, cause))

    private fun storageUnavailable(tag: String, cause: Throwable? = null) = RecordingFailure(
        code = RecordingFailureCode.STORAGE_UNAVAILABLE,
        retryAllowed = true,
        recoveryAction = RecoveryAction.FREE_STORAGE,
        diagnosticTag = tag,
        technicalCause = cause,
    )

    private companion object {
        const val RECORDINGS_DIRECTORY = "SentriCam"
        const val MP4_EXTENSION = "mp4"
        const val WRITE_PROBE_PREFIX = ".sentricam-write-"
        const val WRITE_PROBE_SUFFIX = ".probe"
    }
}
