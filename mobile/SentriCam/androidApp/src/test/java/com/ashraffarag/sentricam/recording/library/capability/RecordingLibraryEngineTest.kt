package com.ashraffarag.sentricam.recording.library.capability

import com.ashraffarag.sentricam.recording.library.domain.RecordingDateFilter
import com.ashraffarag.sentricam.recording.library.domain.RecordingDateGroup
import com.ashraffarag.sentricam.recording.library.domain.RecordingDuration
import com.ashraffarag.sentricam.recording.library.domain.RecordingLibraryQuery
import com.ashraffarag.sentricam.recording.library.domain.RecordingMetadata
import com.ashraffarag.sentricam.recording.library.domain.RecordingStorageInfo
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingLibraryEngineTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val now = timestamp(2026, Calendar.JULY, 30, 15, 0)
    private val today = storage("today", timestamp(2026, Calendar.JULY, 30, 14, 30))
    private val yesterday = storage("yesterday", timestamp(2026, Calendar.JULY, 29, 9, 15))
    private val older = storage("older", timestamp(2026, Calendar.JULY, 20, 18, 0))

    @Test
    fun recordingsAreNewestFirstAndGroupedByLocalDay() {
        val engine = engine(today, older, yesterday)

        engine.refresh()
        val sections = engine.query(RecordingLibraryQuery(), now, utc, Locale.US)

        assertEquals(
            listOf(RecordingDateGroup.TODAY, RecordingDateGroup.YESTERDAY, RecordingDateGroup.OLDER),
            sections.map { it.group },
        )
        assertEquals("today", sections.first().recordings.single().id)
    }

    @Test
    fun dateFilterLimitsResultsWithoutRescanning() {
        val scanner = FakeScanner(listOf(today, yesterday, older))
        val engine = engine(scanner)

        engine.refresh()
        val sections = engine.query(
            RecordingLibraryQuery(filter = RecordingDateFilter.YESTERDAY),
            now,
            utc,
            Locale.US,
        )

        assertEquals(1, scanner.scanCount)
        assertEquals(listOf("yesterday"), sections.flatMap { it.recordings }.map { it.id })
    }

    @Test
    fun searchMatchesDateAndTime() {
        val engine = engine(today, yesterday, older)
        engine.refresh()

        val dateMatch = engine.query(RecordingLibraryQuery("29/07/2026"), now, utc, Locale.US)
        val timeMatch = engine.query(RecordingLibraryQuery("14:30"), now, utc, Locale.US)

        assertEquals(listOf("yesterday"), dateMatch.flatMap { it.recordings }.map { it.id })
        assertEquals(listOf("today"), timeMatch.flatMap { it.recordings }.map { it.id })
    }

    @Test
    fun successfulDeletionRemovesTheCachedEntry() {
        val engine = engine(today)
        engine.refresh()

        assertEquals(RecordingDeletionResult.Deleted, engine.delete("today"))
        assertNull(engine.find("today"))
    }

    @Test
    fun pendingUploadBlockSurfacesAndForcedDeletionBypassesIt() {
        val engine = RecordingLibraryEngine(
            scanner = FakeScanner(listOf(today)),
            metadataReader = fixedMetadataReader,
            deletionService = object : RecordingDeletionService {
                override fun delete(
                    storage: RecordingStorageInfo,
                    force: Boolean,
                ): RecordingFileDeletionOutcome = if (force) {
                    RecordingFileDeletionOutcome.DELETED
                } else {
                    RecordingFileDeletionOutcome.BLOCKED_PENDING_UPLOAD
                }
            },
        )
        engine.refresh()

        assertEquals(RecordingDeletionResult.BlockedPendingUpload, engine.delete("today"))
        assertEquals(RecordingDeletionResult.Deleted, engine.delete("today", force = true))
    }

    private val fixedMetadataReader = object : RecordingMetadataReader {
        override fun read(storage: RecordingStorageInfo): RecordingMetadata = RecordingMetadata(
            startedAtMillis = storage.lastModifiedMillis,
            finishedAtMillis = storage.lastModifiedMillis + 1_000L,
            duration = RecordingDuration(1_000L),
            width = 1280,
            height = 720,
            audioEnabled = true,
        )
    }

    private fun engine(vararg files: RecordingStorageInfo): RecordingLibraryEngine =
        engine(FakeScanner(files.toList()))

    private fun engine(scanner: FakeScanner): RecordingLibraryEngine = RecordingLibraryEngine(
        scanner = scanner,
        metadataReader = fixedMetadataReader,
        deletionService = object : RecordingDeletionService {
            override fun delete(
                storage: RecordingStorageInfo,
                force: Boolean,
            ): RecordingFileDeletionOutcome = RecordingFileDeletionOutcome.DELETED
        },
    )

    private fun storage(id: String, timestamp: Long) = RecordingStorageInfo(
        id = id,
        fileName = "$id.mp4",
        absolutePath = "/recordings/$id.mp4",
        sizeBytes = 1_024L,
        lastModifiedMillis = timestamp,
    )

    private fun timestamp(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis

    private class FakeScanner(private val files: List<RecordingStorageInfo>) : RecordingScanner {
        var scanCount = 0

        override fun scan(): List<RecordingStorageInfo> {
            scanCount++
            return files
        }
    }
}
