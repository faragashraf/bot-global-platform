package com.ashraffarag.sentricam.recording.library.android.storage

import android.content.Context
import android.os.Environment
import com.ashraffarag.sentricam.recording.library.capability.RecordingScanner
import com.ashraffarag.sentricam.recording.library.domain.RecordingStorageInfo
import java.io.File

class AppSpecificRecordingScanner(context: Context) : RecordingScanner {
    private val applicationContext = context.applicationContext

    override fun scan(): List<RecordingStorageInfo> {
        RecordingSidecarMetadataStore.awaitPendingWrites()
        val directory = recordingsDirectory(applicationContext) ?: return emptyList()
        if (!directory.exists()) return emptyList()
        check(directory.isDirectory) { "The recordings path is not a directory" }

        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals(MP4_EXTENSION, ignoreCase = true) }
            .map { file ->
                RecordingStorageInfo(
                    id = file.name,
                    fileName = file.name,
                    absolutePath = file.absolutePath,
                    sizeBytes = file.length().coerceAtLeast(0L),
                    lastModifiedMillis = file.lastModified().coerceAtLeast(0L),
                )
            }
            .toList()
    }

    companion object {
        const val RECORDINGS_DIRECTORY = "SentriCam"
        private const val MP4_EXTENSION = "mp4"

        fun recordingsDirectory(context: Context): File? =
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?.let { moviesDirectory -> File(moviesDirectory, RECORDINGS_DIRECTORY) }
    }
}
