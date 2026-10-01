package com.ashraffarag.sentricam.recording.library.capability

import com.ashraffarag.sentricam.recording.library.domain.RecordingEntry
import com.ashraffarag.sentricam.recording.library.domain.RecordingMetadata
import com.ashraffarag.sentricam.recording.library.domain.RecordingStorageInfo

interface RecordingScanner {
    fun scan(): List<RecordingStorageInfo>
}

interface RecordingMetadataReader {
    fun read(storage: RecordingStorageInfo): RecordingMetadata?
}

interface RecordingDeletionService {
    fun delete(storage: RecordingStorageInfo, force: Boolean = false): RecordingFileDeletionOutcome
}

enum class RecordingFileDeletionOutcome {
    DELETED,
    BLOCKED_PENDING_UPLOAD,
    FAILED,
}

sealed interface RecordingDeletionResult {
    data object Deleted : RecordingDeletionResult

    data object NotFound : RecordingDeletionResult

    data object BlockedPendingUpload : RecordingDeletionResult

    data object Failed : RecordingDeletionResult
}

sealed interface RecordingRefreshResult {
    data class Loaded(val recordings: List<RecordingEntry>) : RecordingRefreshResult

    data class Failed(val cause: Throwable) : RecordingRefreshResult
}
