package com.ashraffarag.sentricam.recording.library.capability

import com.ashraffarag.sentricam.recording.library.domain.RecordingDateFilter
import com.ashraffarag.sentricam.recording.library.domain.RecordingDateGroup
import com.ashraffarag.sentricam.recording.library.domain.RecordingEntry
import com.ashraffarag.sentricam.recording.library.domain.RecordingLibraryQuery
import com.ashraffarag.sentricam.recording.library.domain.RecordingSection
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class RecordingLibraryEngine(
    private val scanner: RecordingScanner,
    private val metadataReader: RecordingMetadataReader,
    private val deletionService: RecordingDeletionService,
) {
    private val lock = Any()
    private var cachedRecordings: List<RecordingEntry> = emptyList()

    fun refresh(): RecordingRefreshResult = try {
        val recordings = scanner.scan()
            .mapNotNull { storage ->
                metadataReader.read(storage)?.let { metadata ->
                    RecordingEntry(
                        id = storage.id,
                        fileName = storage.fileName,
                        metadata = metadata,
                        storage = storage,
                    )
                }
            }
            .sortedByDescending { it.metadata.startedAtMillis }
        synchronized(lock) { cachedRecordings = recordings }
        RecordingRefreshResult.Loaded(recordings)
    } catch (failure: Throwable) {
        RecordingRefreshResult.Failed(failure)
    }

    fun query(
        query: RecordingLibraryQuery,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): List<RecordingSection> {
        val recordings = synchronized(lock) { cachedRecordings }
        val todayStart = startOfDay(nowMillis, timeZone)
        val yesterdayStart = previousDayStart(todayStart, timeZone)
        val weekStart = startOfWeek(nowMillis, timeZone)
        val normalizedSearch = query.searchText.trim()

        val visible = recordings.filter { recording ->
            matchesFilter(
                startedAtMillis = recording.metadata.startedAtMillis,
                filter = query.filter,
                todayStart = todayStart,
                yesterdayStart = yesterdayStart,
                weekStart = weekStart,
            ) && matchesSearch(recording, normalizedSearch, timeZone, locale)
        }

        return RecordingDateGroup.entries.mapNotNull { group ->
            val grouped = visible.filter { recording ->
                groupFor(recording.metadata.startedAtMillis, todayStart, yesterdayStart) == group
            }
            if (grouped.isEmpty()) null else RecordingSection(group, grouped)
        }
    }

    fun find(recordingId: String): RecordingEntry? = synchronized(lock) {
        cachedRecordings.firstOrNull { it.id == recordingId }
    }

    fun delete(recordingId: String, force: Boolean = false): RecordingDeletionResult {
        val recording = find(recordingId) ?: return RecordingDeletionResult.NotFound
        return when (deletionService.delete(recording.storage, force)) {
            RecordingFileDeletionOutcome.DELETED -> {
                synchronized(lock) {
                    cachedRecordings = cachedRecordings.filterNot { it.id == recordingId }
                }
                RecordingDeletionResult.Deleted
            }
            RecordingFileDeletionOutcome.BLOCKED_PENDING_UPLOAD -> RecordingDeletionResult.BlockedPendingUpload
            RecordingFileDeletionOutcome.FAILED -> RecordingDeletionResult.Failed
        }
    }

    private fun matchesFilter(
        startedAtMillis: Long,
        filter: RecordingDateFilter,
        todayStart: Long,
        yesterdayStart: Long,
        weekStart: Long,
    ): Boolean = when (filter) {
        RecordingDateFilter.TODAY -> startedAtMillis >= todayStart
        RecordingDateFilter.YESTERDAY -> startedAtMillis in yesterdayStart until todayStart
        RecordingDateFilter.THIS_WEEK -> startedAtMillis >= weekStart
        RecordingDateFilter.ALL -> true
    }

    private fun matchesSearch(
        recording: RecordingEntry,
        searchText: String,
        timeZone: TimeZone,
        locale: Locale,
    ): Boolean {
        if (searchText.isBlank()) return true
        val timestamp = Date(recording.metadata.startedAtMillis)
        val searchableValues = SEARCH_PATTERNS.flatMap { pattern ->
            listOf(
                format(timestamp, pattern, timeZone, Locale.US),
                format(timestamp, pattern, timeZone, locale),
            )
        }
        return searchableValues.any { it.contains(searchText, ignoreCase = true) }
    }

    private fun groupFor(
        startedAtMillis: Long,
        todayStart: Long,
        yesterdayStart: Long,
    ): RecordingDateGroup = when {
        startedAtMillis >= todayStart -> RecordingDateGroup.TODAY
        startedAtMillis >= yesterdayStart -> RecordingDateGroup.YESTERDAY
        else -> RecordingDateGroup.OLDER
    }

    private fun startOfDay(timestampMillis: Long, timeZone: TimeZone): Long =
        Calendar.getInstance(timeZone).apply {
            timeInMillis = timestampMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun previousDayStart(todayStart: Long, timeZone: TimeZone): Long =
        Calendar.getInstance(timeZone).apply {
            timeInMillis = todayStart
            add(Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis

    private fun startOfWeek(timestampMillis: Long, timeZone: TimeZone): Long =
        Calendar.getInstance(timeZone).apply {
            timeInMillis = timestampMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            while (get(Calendar.DAY_OF_WEEK) != firstDayOfWeek) {
                add(Calendar.DAY_OF_YEAR, -1)
            }
        }.timeInMillis

    private fun format(
        timestamp: Date,
        pattern: String,
        timeZone: TimeZone,
        locale: Locale,
    ): String = SimpleDateFormat(pattern, locale).apply {
        this.timeZone = timeZone
    }.format(timestamp)

    private companion object {
        val SEARCH_PATTERNS = listOf(
            "dd/MM/yyyy",
            "yyyy-MM-dd",
            "dd-MM-yyyy",
            "HH:mm",
            "HH:mm:ss",
        )
    }
}
