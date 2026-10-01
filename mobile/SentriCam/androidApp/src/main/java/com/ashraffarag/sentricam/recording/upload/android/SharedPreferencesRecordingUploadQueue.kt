package com.ashraffarag.sentricam.recording.upload.android

import android.content.Context
import com.ashraffarag.sentricam.recording.upload.domain.RecordingDeletionPermission
import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadCandidate
import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadItem
import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadQueue
import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadReceipt
import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadState
import com.google.gson.Gson

class SharedPreferencesRecordingUploadQueue(
    context: Context,
    private val gson: Gson = Gson(),
) : RecordingUploadQueue {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun enqueue(candidate: RecordingUploadCandidate): RecordingUploadItem = synchronized(lock) {
        val current = read()
        current.firstOrNull { it.clientRecordingId == candidate.clientRecordingId } ?: RecordingUploadItem(
            clientRecordingId = candidate.clientRecordingId,
            sessionId = candidate.sessionId,
            absolutePath = candidate.absolutePath,
            fileName = candidate.fileName,
            durationMillis = candidate.durationMillis,
            sizeBytes = candidate.sizeBytes,
            createdAtMillis = candidate.createdAtMillis,
            motion = candidate.motion,
            manual = candidate.manual,
        ).also { item -> write(current + item) }
    }

    override fun get(clientRecordingId: String): RecordingUploadItem? = synchronized(lock) {
        read().firstOrNull { it.clientRecordingId == clientRecordingId }
    }

    override fun latest(): RecordingUploadItem? = synchronized(lock) {
        read().maxWithOrNull(compareBy<RecordingUploadItem> { it.createdAtMillis }.thenBy { it.clientRecordingId })
    }

    fun observe(listener: () -> Unit): AutoCloseable {
        val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_QUEUE) listener()
        }
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        return AutoCloseable { preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener) }
    }

    override fun pending(): List<RecordingUploadItem> = synchronized(lock) {
        read().filter { it.state == RecordingUploadState.QUEUED || it.state == RecordingUploadState.RETRYING }
    }

    override fun recoverInterrupted(): List<RecordingUploadItem> = synchronized(lock) {
        val recovered = read().map { item ->
            if (item.state == RecordingUploadState.UPLOADING) {
                item.copy(state = RecordingUploadState.RETRYING, progressPercent = 0)
            } else {
                item
            }
        }
        write(recovered)
        recovered.filter { it.state == RecordingUploadState.RETRYING }
    }

    override fun updateChecksum(clientRecordingId: String, checksumSha256: String) = mutate(clientRecordingId) {
        it.copy(checksumSha256 = checksumSha256.lowercase())
    }

    override fun markUploading(clientRecordingId: String, attemptCount: Int) = mutate(clientRecordingId) {
        it.copy(
            state = RecordingUploadState.UPLOADING,
            progressPercent = 0,
            attemptCount = attemptCount,
            lastErrorCode = null,
        )
    }

    override fun updateProgress(clientRecordingId: String, progressPercent: Int) = mutate(clientRecordingId) {
        it.copy(progressPercent = progressPercent.coerceIn(0, 100))
    }

    override fun markRetrying(clientRecordingId: String, attemptCount: Int, errorCode: String) =
        mutate(clientRecordingId) {
            it.copy(
                state = RecordingUploadState.RETRYING,
                progressPercent = 0,
                attemptCount = attemptCount,
                lastErrorCode = errorCode,
            )
        }

    override fun markUploaded(
        clientRecordingId: String,
        receipt: RecordingUploadReceipt,
        uploadedAtMillis: Long,
    ) = mutate(clientRecordingId) {
        it.copy(
            state = RecordingUploadState.UPLOADED,
            checksumSha256 = receipt.checksumSha256.lowercase(),
            serverRecordingId = receipt.serverRecordingId,
            progressPercent = 100,
            lastErrorCode = null,
            uploadedAtMillis = uploadedAtMillis,
        )
    }

    override fun markFailed(clientRecordingId: String, attemptCount: Int, errorCode: String) =
        mutate(clientRecordingId) {
            it.copy(
                state = RecordingUploadState.FAILED,
                progressPercent = 0,
                attemptCount = attemptCount,
                lastErrorCode = errorCode,
            )
        }

    override fun resetFailed(): List<RecordingUploadItem> = synchronized(lock) {
        val resetIds = mutableSetOf<String>()
        val reset = read().map { item ->
            if (item.state == RecordingUploadState.FAILED &&
                item.lastErrorCode in RECOVERABLE_FAILURE_CODES
            ) {
                resetIds += item.clientRecordingId
                item.copy(state = RecordingUploadState.QUEUED, attemptCount = 0, lastErrorCode = null)
            } else {
                item
            }
        }
        write(reset)
        reset.filter { it.clientRecordingId in resetIds }
    }

    override fun deletionPermission(absolutePath: String): RecordingDeletionPermission = synchronized(lock) {
        when (val item = read().firstOrNull { it.absolutePath == absolutePath }) {
            null -> RecordingDeletionPermission.ALLOWED
            else -> if (item.state == RecordingUploadState.UPLOADED) {
                RecordingDeletionPermission.ALLOWED
            } else if (
                item.state == RecordingUploadState.FAILED &&
                item.lastErrorCode !in RECOVERABLE_FAILURE_CODES
            ) {
                RecordingDeletionPermission.ALLOWED
            } else {
                RecordingDeletionPermission.BLOCKED_PENDING_UPLOAD
            }
        }
    }

    private fun mutate(
        clientRecordingId: String,
        transform: (RecordingUploadItem) -> RecordingUploadItem,
    ): RecordingUploadItem? = synchronized(lock) {
        val current = read()
        val index = current.indexOfFirst { it.clientRecordingId == clientRecordingId }
        if (index < 0) return@synchronized null
        val updated = transform(current[index])
        write(current.toMutableList().apply { this[index] = updated })
        updated
    }

    private fun read(): List<RecordingUploadItem> {
        val json = preferences.getString(KEY_QUEUE, null) ?: return emptyList()
        return try {
            gson.fromJson(json, QueuePayload::class.java)?.items.orEmpty()
        } catch (_: RuntimeException) {
            check(preferences.edit().remove(KEY_QUEUE).commit()) { "Corrupt upload queue cleanup failed" }
            emptyList()
        }
    }

    private fun write(items: List<RecordingUploadItem>) {
        check(preferences.edit().putString(KEY_QUEUE, gson.toJson(QueuePayload(items = items))).commit()) {
            "Recording upload queue persistence failed"
        }
    }

    private data class QueuePayload(
        val version: Int = SCHEMA_VERSION,
        val items: List<RecordingUploadItem> = emptyList(),
    )

    private companion object {
        const val PREFERENCES = "recording_upload_queue"
        const val KEY_QUEUE = "queue"
        const val SCHEMA_VERSION = 1
        val RECOVERABLE_FAILURE_CODES = setOf(
            "network",
            "timeout",
            "unexpected",
            "unauthorized",
            "invalid_configuration",
        )
        val lock = Any()
    }
}
