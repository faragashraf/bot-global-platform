package com.ashraffarag.sentricam.recording.upload.android

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadScheduler
import java.util.concurrent.TimeUnit

class WorkManagerRecordingUploadScheduler(context: Context) : RecordingUploadScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun schedule(clientRecordingId: String, replaceCompleted: Boolean) {
        val request = OneTimeWorkRequestBuilder<RecordingUploadWorker>()
            .setInputData(workDataOf(RecordingUploadWorker.KEY_CLIENT_RECORDING_ID to clientRecordingId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(TAG_RECORDING_UPLOAD)
            .addTag("$TAG_RECORDING_UPLOAD:$clientRecordingId")
            .build()
        workManager.enqueueUniqueWork(
            "$UNIQUE_WORK_PREFIX$clientRecordingId",
            if (replaceCompleted) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
        Log.i(TAG_RECORDING_UPLOAD, "event=worker_scheduled recording=$clientRecordingId replace=$replaceCompleted")
    }

    companion object {
        const val TAG_RECORDING_UPLOAD = "sentricam-recording-upload"
        private const val UNIQUE_WORK_PREFIX = "recording-upload:"
    }
}
