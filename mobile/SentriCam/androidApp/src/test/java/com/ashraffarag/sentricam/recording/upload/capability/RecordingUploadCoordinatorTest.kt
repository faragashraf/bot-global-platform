package com.ashraffarag.sentricam.recording.upload.capability

import com.ashraffarag.sentricam.recording.engine.domain.RecordingLens
import com.ashraffarag.sentricam.recording.engine.domain.RecordingQuality
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSegmentMetadata
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSegmentStatus
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSessionResult
import com.ashraffarag.sentricam.recording.engine.domain.RecordingStartReason
import com.ashraffarag.sentricam.recording.engine.domain.RecordingTriggerContext
import com.ashraffarag.sentricam.recording.engine.domain.StopReason
import com.ashraffarag.sentricam.recording.upload.domain.RecordingDeletionPermission
import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadCandidate
import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadItem
import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadQueue
import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadReceipt
import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingUploadCoordinatorTest {
    @Test
    fun completedFinalizedSegmentsEnterPersistentQueueOnce() {
        val queue = FakeQueue()
        val scheduled = mutableListOf<String>()
        val coordinator = RecordingUploadCoordinator(queue) { id, _ -> scheduled += id }
        val result = RecordingSessionResult(
            "session",
            listOf(segment("segment-1", finalized = true), segment("failed", finalized = false)),
            StopReason.USER,
        )

        coordinator.enqueueCompleted(result, schedule = true)
        coordinator.enqueueCompleted(result, schedule = true)

        assertEquals(listOf("segment-1"), queue.items.keys.toList())
        assertEquals(listOf("segment-1"), scheduled)
        assertTrue(queue.items.getValue("segment-1").motion)
        assertFalse(queue.items.getValue("segment-1").manual)
    }

    @Test
    fun durableSegmentFinalizationQueuesAndSchedulesBeforeSessionCompletion() {
        val queue = FakeQueue()
        val scheduled = mutableListOf<String>()
        val coordinator = RecordingUploadCoordinator(queue) { id, _ -> scheduled += id }

        assertTrue(coordinator.enqueueCompleted(segment("segment-early", finalized = true), schedule = true))
        assertFalse(coordinator.enqueueCompleted(segment("segment-early", finalized = true), schedule = true))

        assertEquals(listOf("segment-early"), queue.items.keys.toList())
        assertEquals(listOf("segment-early"), scheduled)
    }

    @Test
    fun interruptedUploadIsRecoveredAndScheduledWithoutDeletingLocalFile() {
        val queue = FakeQueue().apply {
            enqueue(candidate("segment-1"))
            markUploading("segment-1", 1)
        }
        val scheduled = mutableListOf<String>()
        val coordinator = RecordingUploadCoordinator(queue) { id, _ -> scheduled += id }

        val restored = coordinator.restoreAndSchedule()

        assertEquals(1, restored)
        assertEquals(RecordingUploadState.RETRYING, queue.get("segment-1")?.state)
        assertEquals(listOf("segment-1"), scheduled)
        assertEquals(
            RecordingDeletionPermission.BLOCKED_PENDING_UPLOAD,
            queue.deletionPermission("/recordings/segment-1.mp4"),
        )
    }

    @Test
    fun localDeletionBecomesAllowedOnlyAfterServerReceiptIsPersisted() {
        val queue = FakeQueue()
        queue.enqueue(candidate("segment-1"))
        assertEquals(
            RecordingDeletionPermission.BLOCKED_PENDING_UPLOAD,
            queue.deletionPermission("/recordings/segment-1.mp4"),
        )

        queue.markUploaded(
            "segment-1",
            RecordingUploadReceipt("server-id", "a".repeat(64), duplicate = false),
            2_000,
        )

        assertEquals(
            RecordingDeletionPermission.ALLOWED,
            queue.deletionPermission("/recordings/segment-1.mp4"),
        )
    }

    @Test
    fun terminalUploadFailureNoLongerBlocksLocalDeletion() {
        val queue = FakeQueue().apply {
            enqueue(candidate("segment-1"))
            markFailed("segment-1", 5, "rejected")
        }

        assertEquals(
            RecordingDeletionPermission.ALLOWED,
            queue.deletionPermission("/recordings/segment-1.mp4"),
        )
    }

    @Test
    fun recoverableUploadFailureKeepsBlockingLocalDeletion() {
        val queue = FakeQueue().apply {
            enqueue(candidate("segment-1"))
            markFailed("segment-1", 5, "network")
        }

        assertEquals(
            RecordingDeletionPermission.BLOCKED_PENDING_UPLOAD,
            queue.deletionPermission("/recordings/segment-1.mp4"),
        )
    }

    @Test
    fun recordingsMissingFromTheUploadQueueAreImmediatelyDeletable() {
        val queue = FakeQueue()

        assertEquals(
            RecordingDeletionPermission.ALLOWED,
            queue.deletionPermission("/recordings/legacy.mp4"),
        )
    }

    @Test
    fun retrySafeFailureUsesReplacementWorkAfterFreshRegistration() {
        val queue = FakeQueue().apply {
            enqueue(candidate("segment-1"))
            markFailed("segment-1", 5, "unauthorized")
        }
        val scheduled = mutableListOf<Pair<String, Boolean>>()
        val coordinator = RecordingUploadCoordinator(queue) { id, replace -> scheduled += id to replace }

        assertEquals(1, coordinator.retryFailedAfterRegistration())

        assertEquals(RecordingUploadState.QUEUED, queue.get("segment-1")?.state)
        assertEquals(listOf("segment-1" to true), scheduled)
    }

    private fun segment(id: String, finalized: Boolean) = RecordingSegmentMetadata(
        sessionId = "session",
        segmentId = id,
        segmentIndex = 1,
        createdAtMillis = 900,
        startedAtMillis = 1_000,
        endedAtMillis = 2_000,
        durationMillis = 1_000,
        fileSizeBytes = 4,
        width = 1280,
        height = 720,
        rotationDegrees = 0,
        lens = RecordingLens.BACK,
        requestedQuality = RecordingQuality.HD,
        actualQuality = RecordingQuality.HD,
        audioEnabled = true,
        timestampOverlayEnabled = false,
        status = RecordingSegmentStatus.COMPLETED,
        finalized = finalized,
        absolutePath = "/recordings/$id.mp4",
        triggerContext = RecordingTriggerContext(RecordingStartReason.MOTION),
    )

    private fun candidate(id: String) = RecordingUploadCandidate(
        id,
        "session",
        "/recordings/$id.mp4",
        "$id.mp4",
        1_000,
        4,
        1_000,
        motion = false,
        manual = true,
    )

    private class FakeQueue : RecordingUploadQueue {
        val items = linkedMapOf<String, RecordingUploadItem>()

        override fun enqueue(candidate: RecordingUploadCandidate): RecordingUploadItem =
            items.getOrPut(candidate.clientRecordingId) {
                RecordingUploadItem(
                    candidate.clientRecordingId,
                    candidate.sessionId,
                    candidate.absolutePath,
                    candidate.fileName,
                    candidate.durationMillis,
                    candidate.sizeBytes,
                    candidate.createdAtMillis,
                    candidate.motion,
                    candidate.manual,
                )
            }

        override fun get(clientRecordingId: String) = items[clientRecordingId]
        override fun pending() = items.values.filter {
            it.state == RecordingUploadState.QUEUED || it.state == RecordingUploadState.RETRYING
        }
        override fun recoverInterrupted(): List<RecordingUploadItem> {
            items.replaceAll { _, item ->
                if (item.state == RecordingUploadState.UPLOADING) item.copy(state = RecordingUploadState.RETRYING)
                else item
            }
            return pending()
        }
        override fun updateChecksum(clientRecordingId: String, checksumSha256: String) = update(clientRecordingId) {
            it.copy(checksumSha256 = checksumSha256)
        }
        override fun markUploading(clientRecordingId: String, attemptCount: Int) = update(clientRecordingId) {
            it.copy(state = RecordingUploadState.UPLOADING, attemptCount = attemptCount)
        }
        override fun updateProgress(clientRecordingId: String, progressPercent: Int) = update(clientRecordingId) {
            it.copy(progressPercent = progressPercent)
        }
        override fun markRetrying(clientRecordingId: String, attemptCount: Int, errorCode: String) =
            update(clientRecordingId) {
                it.copy(state = RecordingUploadState.RETRYING, attemptCount = attemptCount, lastErrorCode = errorCode)
            }
        override fun markUploaded(
            clientRecordingId: String,
            receipt: RecordingUploadReceipt,
            uploadedAtMillis: Long,
        ) = update(clientRecordingId) {
            it.copy(
                state = RecordingUploadState.UPLOADED,
                serverRecordingId = receipt.serverRecordingId,
                uploadedAtMillis = uploadedAtMillis,
            )
        }
        override fun markFailed(clientRecordingId: String, attemptCount: Int, errorCode: String) =
            update(clientRecordingId) {
                it.copy(state = RecordingUploadState.FAILED, attemptCount = attemptCount, lastErrorCode = errorCode)
            }
        override fun resetFailed(): List<RecordingUploadItem> {
            val reset = items.values.filter {
                it.state == RecordingUploadState.FAILED &&
                    it.lastErrorCode in setOf("network", "timeout", "unexpected", "unauthorized", "invalid_configuration")
            }.map { it.copy(state = RecordingUploadState.QUEUED, attemptCount = 0, lastErrorCode = null) }
            reset.forEach { items[it.clientRecordingId] = it }
            return reset
        }
        override fun deletionPermission(absolutePath: String): RecordingDeletionPermission {
            val item = items.values.firstOrNull { it.absolutePath == absolutePath } ?: return RecordingDeletionPermission.ALLOWED
            return if (item.state == RecordingUploadState.UPLOADED) {
                RecordingDeletionPermission.ALLOWED
            } else if (item.state == RecordingUploadState.FAILED && item.lastErrorCode !in RECOVERABLE_FAILURE_CODES) {
                RecordingDeletionPermission.ALLOWED
            } else {
                RecordingDeletionPermission.BLOCKED_PENDING_UPLOAD
            }
        }

        private fun update(id: String, transform: (RecordingUploadItem) -> RecordingUploadItem): RecordingUploadItem? {
            val item = items[id] ?: return null
            return transform(item).also { items[id] = it }
        }

        private companion object {
            val RECOVERABLE_FAILURE_CODES =
                setOf("network", "timeout", "unexpected", "unauthorized", "invalid_configuration")
        }
    }
}
