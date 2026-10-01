package com.ashraffarag.sentricam.recording.upload.domain

data class RecordingUploadCandidate(
    val clientRecordingId: String,
    val sessionId: String,
    val absolutePath: String,
    val fileName: String,
    val durationMillis: Long,
    val sizeBytes: Long,
    val createdAtMillis: Long,
    val motion: Boolean,
    val manual: Boolean,
)

enum class RecordingUploadState {
    QUEUED,
    UPLOADING,
    RETRYING,
    UPLOADED,
    FAILED,
}

data class RecordingUploadItem(
    val clientRecordingId: String,
    val sessionId: String,
    val absolutePath: String,
    val fileName: String,
    val durationMillis: Long,
    val sizeBytes: Long,
    val createdAtMillis: Long,
    val motion: Boolean,
    val manual: Boolean,
    val state: RecordingUploadState = RecordingUploadState.QUEUED,
    val checksumSha256: String? = null,
    val serverRecordingId: String? = null,
    val progressPercent: Int = 0,
    val attemptCount: Int = 0,
    val lastErrorCode: String? = null,
    val uploadedAtMillis: Long? = null,
)

data class RecordingUploadReceipt(
    val serverRecordingId: String,
    val checksumSha256: String,
    val duplicate: Boolean,
)

enum class RecordingDeletionPermission {
    ALLOWED,
    BLOCKED_PENDING_UPLOAD,
}

sealed class RecordingUploadFailure(
    val code: String,
    val retryAllowed: Boolean,
    internal val technicalCause: Throwable? = null,
) {
    class Network(cause: Throwable? = null) : RecordingUploadFailure("network", true, cause)
    class Timeout(cause: Throwable? = null) : RecordingUploadFailure("timeout", true, cause)
    data object Unauthorized : RecordingUploadFailure("unauthorized", false)
    data object Conflict : RecordingUploadFailure("conflict", false)
    data object Rejected : RecordingUploadFailure("rejected", false)
    data object FileUnavailable : RecordingUploadFailure("file_unavailable", false)
    data object InvalidConfiguration : RecordingUploadFailure("invalid_configuration", false)
    class Unexpected(cause: Throwable? = null) : RecordingUploadFailure("unexpected", true, cause)
}

sealed interface RecordingUploadCallResult {
    data class Uploaded(val receipt: RecordingUploadReceipt) : RecordingUploadCallResult
    data object NotFound : RecordingUploadCallResult
    data class Failed(val failure: RecordingUploadFailure) : RecordingUploadCallResult
}

interface RecordingUploadQueue {
    fun enqueue(candidate: RecordingUploadCandidate): RecordingUploadItem
    fun get(clientRecordingId: String): RecordingUploadItem?
    fun latest(): RecordingUploadItem? = null
    fun pending(): List<RecordingUploadItem>
    fun recoverInterrupted(): List<RecordingUploadItem>
    fun updateChecksum(clientRecordingId: String, checksumSha256: String): RecordingUploadItem?
    fun markUploading(clientRecordingId: String, attemptCount: Int): RecordingUploadItem?
    fun updateProgress(clientRecordingId: String, progressPercent: Int): RecordingUploadItem?
    fun markRetrying(clientRecordingId: String, attemptCount: Int, errorCode: String): RecordingUploadItem?
    fun markUploaded(
        clientRecordingId: String,
        receipt: RecordingUploadReceipt,
        uploadedAtMillis: Long,
    ): RecordingUploadItem?
    fun markFailed(clientRecordingId: String, attemptCount: Int, errorCode: String): RecordingUploadItem?
    fun resetFailed(): List<RecordingUploadItem>
    fun deletionPermission(absolutePath: String): RecordingDeletionPermission
}

fun interface RecordingUploadScheduler {
    fun schedule(clientRecordingId: String, replaceCompleted: Boolean)
}

fun interface RecordingUploadChecksum {
    fun sha256(absolutePath: String): String
}

fun interface RecordingUploadClock {
    fun nowMillis(): Long
}
