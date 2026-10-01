package com.ashraffarag.sentricam.recording.upload.android

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ashraffarag.sentricam.device.registration.RegistrationCallResult
import com.ashraffarag.sentricam.device.registration.ServerUrlPolicy
import com.ashraffarag.sentricam.device.registration.android.AndroidServerConfiguration
import com.ashraffarag.sentricam.device.registration.android.EncryptedDeviceCredentialStore
import com.ashraffarag.sentricam.recording.library.android.storage.AppSpecificRecordingScanner
import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadCallResult
import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadFailure
import java.io.File

class RecordingUploadWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    private val queue = SharedPreferencesRecordingUploadQueue(appContext)
    private val checksum = Sha256RecordingUploadChecksum()
    private val credentials = EncryptedDeviceCredentialStore(appContext)
    private val serverConfiguration = AndroidServerConfiguration(appContext)
    private val api = RecordingUploadApiClient()

    override suspend fun doWork(): Result {
        val clientRecordingId = inputData.getString(KEY_CLIENT_RECORDING_ID)
            ?: return Result.failure(workDataOf(KEY_FAILURE_CODE to "missing_recording_id"))
        Log.i(TAG, "event=worker_started recording=$clientRecordingId run=${runAttemptCount + 1}")
        val original = queue.get(clientRecordingId) ?: return Result.success()
        if (original.serverRecordingId != null) return Result.success()
        val attempt = runAttemptCount + 1
        val file = safeRecordingFile(original.absolutePath)
            ?: return fail(clientRecordingId, attempt, RecordingUploadFailure.FileUnavailable)
        if (file.length() != original.sizeBytes) {
            return fail(clientRecordingId, attempt, RecordingUploadFailure.FileUnavailable)
        }
        val deviceCredentials = try {
            credentials.load()
        } catch (_: RuntimeException) {
            null
        } ?: return fail(clientRecordingId, attempt, RecordingUploadFailure.Unauthorized)
        if ((deviceCredentials.details.accessTokenExpiresAtMillis ?: 0L) <= System.currentTimeMillis()) {
            return fail(clientRecordingId, attempt, RecordingUploadFailure.Unauthorized)
        }
        val normalizedUrl = when (
            val result = ServerUrlPolicy(serverConfiguration.isDebug).normalize(serverConfiguration.baseUrl())
        ) {
            is RegistrationCallResult.Success -> result.value
            is RegistrationCallResult.Failure ->
                return fail(clientRecordingId, attempt, RecordingUploadFailure.InvalidConfiguration)
        }
        val checksumValue = original.checksumSha256 ?: try {
            checksum.sha256(file.absolutePath).also { queue.updateChecksum(clientRecordingId, it) }
        } catch (_: RuntimeException) {
            return fail(clientRecordingId, attempt, RecordingUploadFailure.FileUnavailable)
        }
        val item = queue.markUploading(clientRecordingId, attempt)
            ?.copy(checksumSha256 = checksumValue)
            ?: return Result.success()
        Log.i(
            TAG,
            "event=upload_attempt recording=$clientRecordingId device=${deviceCredentials.details.serverDeviceId} attempt=$attempt",
        )

        when (
            val existing = api.findExisting(
                normalizedUrl,
                deviceCredentials.accessToken,
                clientRecordingId,
                checksumValue,
            )
        ) {
            is RecordingUploadCallResult.Uploaded -> {
                queue.markUploaded(clientRecordingId, existing.receipt, System.currentTimeMillis())
                Log.i(TAG, "event=server_accepted recording=$clientRecordingId duplicate=true")
                Log.i(TAG, "event=upload_succeeded recording=$clientRecordingId")
                return Result.success()
            }
            is RecordingUploadCallResult.Failed -> return fail(clientRecordingId, attempt, existing.failure)
            RecordingUploadCallResult.NotFound -> Unit
        }

        return when (
            val upload = api.upload(
                normalizedUrl,
                deviceCredentials.accessToken,
                item,
                file,
            ) { progress ->
                queue.updateProgress(clientRecordingId, progress)
                setProgressAsync(workDataOf(KEY_PROGRESS_PERCENT to progress))
            }
        ) {
            is RecordingUploadCallResult.Uploaded -> {
                queue.markUploaded(clientRecordingId, upload.receipt, System.currentTimeMillis())
                Log.i(
                    TAG,
                    "event=server_accepted recording=$clientRecordingId duplicate=${upload.receipt.duplicate}",
                )
                Log.i(TAG, "event=upload_succeeded recording=$clientRecordingId")
                Result.success(workDataOf(KEY_SERVER_RECORDING_ID to upload.receipt.serverRecordingId))
            }
            is RecordingUploadCallResult.Failed -> fail(clientRecordingId, attempt, upload.failure)
            RecordingUploadCallResult.NotFound ->
                fail(clientRecordingId, attempt, RecordingUploadFailure.Unexpected())
        }
    }

    private fun fail(
        clientRecordingId: String,
        attempt: Int,
        failure: RecordingUploadFailure,
    ): Result {
        val retry = failure.retryAllowed && attempt < MAX_ATTEMPTS
        if (retry) {
            queue.markRetrying(clientRecordingId, attempt, failure.code)
            Log.w(TAG, "event=upload_failed recording=$clientRecordingId code=${failure.code} attempt=$attempt")
            Log.i(TAG, "event=retry_scheduled recording=$clientRecordingId next=${attempt + 1}")
            return Result.retry()
        }
        queue.markFailed(clientRecordingId, attempt, failure.code)
        Log.w(TAG, "event=upload_failed recording=$clientRecordingId code=${failure.code} attempt=$attempt final=true")
        return Result.failure(workDataOf(KEY_FAILURE_CODE to failure.code))
    }

    private fun safeRecordingFile(absolutePath: String): File? = try {
        val root = AppSpecificRecordingScanner.recordingsDirectory(applicationContext)?.canonicalFile
            ?: return null
        val target = File(absolutePath).canonicalFile
        target.takeIf { it.parentFile == root && it.isFile }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val KEY_CLIENT_RECORDING_ID = "client_recording_id"
        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_SERVER_RECORDING_ID = "server_recording_id"
        const val KEY_FAILURE_CODE = "failure_code"
        const val MAX_ATTEMPTS = 5
        private const val TAG = "RecordingUploadWorker"
    }
}
