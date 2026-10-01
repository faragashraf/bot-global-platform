package com.ashraffarag.sentricam.recording.library.domain

data class RecordingSessionDisplayInfo(
    val shortSessionId: String?,
    val segmentIndex: Int?,
    val segmentCount: Int,
) {
    val isMultiSegment: Boolean
        get() = shortSessionId != null && segmentCount > 1
}

object RecordingSessionGrouping {
    fun build(entries: List<RecordingEntry>): Map<String, RecordingSessionDisplayInfo> {
        val counts = entries.mapNotNull { it.metadata.sessionId }
            .groupingBy { it }
            .eachCount()
        return entries.associate { entry ->
            val sessionId = entry.metadata.sessionId
            entry.id to RecordingSessionDisplayInfo(
                shortSessionId = sessionId?.take(SHORT_SESSION_ID_LENGTH),
                segmentIndex = entry.metadata.segmentIndex,
                segmentCount = sessionId?.let { counts[it] } ?: 1,
            )
        }
    }

    private const val SHORT_SESSION_ID_LENGTH = 8
}
