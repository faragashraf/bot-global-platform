package com.ashraffarag.sentricam.recording.library.android.storage

import android.content.Context
import android.util.Log
import com.ashraffarag.sentricam.recording.library.capability.RecordingDeletionService
import com.ashraffarag.sentricam.recording.library.capability.RecordingFileDeletionOutcome
import com.ashraffarag.sentricam.recording.library.domain.RecordingStorageInfo
import com.ashraffarag.sentricam.recording.upload.domain.RecordingDeletionPermission
import java.io.File

class AppSpecificRecordingDeletionService(
    context: Context,
    private val deletionPermission: (String) -> RecordingDeletionPermission = {
        RecordingDeletionPermission.ALLOWED
    },
) : RecordingDeletionService {
    private val recordingsDirectory = AppSpecificRecordingScanner.recordingsDirectory(
        context.applicationContext,
    )

    override fun delete(storage: RecordingStorageInfo, force: Boolean): RecordingFileDeletionOutcome {
        val root = recordingsDirectory ?: run {
            Log.w(TAG, "Recordings directory unavailable; cannot delete ${storage.fileName}")
            return RecordingFileDeletionOutcome.FAILED
        }
        return try {
            val rootPath = root.canonicalFile
            val target = File(storage.absolutePath).canonicalFile
            if (target.parentFile != rootPath || !target.isFile) {
                Log.w(TAG, "Refusing to delete ${storage.fileName}: outside recordings directory or missing")
                return RecordingFileDeletionOutcome.FAILED
            }
            if (!force && deletionPermission(target.absolutePath) == RecordingDeletionPermission.BLOCKED_PENDING_UPLOAD) {
                Log.i(TAG, "Deletion of ${storage.fileName} blocked until upload completes")
                return RecordingFileDeletionOutcome.BLOCKED_PENDING_UPLOAD
            }
            target.delete().also { deleted ->
                if (deleted) {
                    RecordingSidecarMetadataStore.delete(target)
                } else {
                    Log.w(TAG, "File.delete() returned false for ${storage.fileName}")
                }
            }.let { deleted ->
                if (deleted) RecordingFileDeletionOutcome.DELETED else RecordingFileDeletionOutcome.FAILED
            }
        } catch (failure: Throwable) {
            Log.e(TAG, "Unable to delete ${storage.fileName}", failure)
            RecordingFileDeletionOutcome.FAILED
        }
    }

    private companion object {
        const val TAG = "RecordingDeletion"
    }
}
