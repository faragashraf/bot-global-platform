package com.ashraffarag.sentricam.recording.library.android

import android.content.Context
import com.ashraffarag.sentricam.recording.library.android.storage.AndroidRecordingMetadataReader
import com.ashraffarag.sentricam.recording.library.android.storage.AppSpecificRecordingDeletionService
import com.ashraffarag.sentricam.recording.library.android.storage.AppSpecificRecordingScanner
import com.ashraffarag.sentricam.recording.library.capability.RecordingLibraryEngine
import com.ashraffarag.sentricam.recording.upload.android.SharedPreferencesRecordingUploadQueue

object RecordingLibraryEngineFactory {
    fun create(context: Context): RecordingLibraryEngine {
        val appContext = context.applicationContext
        val uploadQueue = SharedPreferencesRecordingUploadQueue(appContext)
        return RecordingLibraryEngine(
            scanner = AppSpecificRecordingScanner(appContext),
            metadataReader = AndroidRecordingMetadataReader(),
            deletionService = AppSpecificRecordingDeletionService(
                appContext,
                uploadQueue::deletionPermission,
            ),
        )
    }
}
