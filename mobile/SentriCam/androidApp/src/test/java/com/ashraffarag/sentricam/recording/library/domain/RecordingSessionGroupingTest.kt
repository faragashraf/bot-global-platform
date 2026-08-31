package com.ashraffarag.sentricam.recording.library.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSessionGroupingTest {
    @Test
    fun groupsSegmentsFromOneSessionAndKeepsLegacyFallback() {
        val first = entry("first", "session-abcdefgh", 1)
        val second = entry("second", "session-abcdefgh", 2)
        val legacy = entry("legacy", null, null)

        val grouping = RecordingSessionGrouping.build(listOf(first, second, legacy))

        assertEquals("session-", grouping.getValue("first").shortSessionId)
        assertEquals(2, grouping.getValue("first").segmentCount)
        assertTrue(grouping.getValue("first").isMultiSegment)
        assertEquals(2, grouping.getValue("second").segmentIndex)
        assertNull(grouping.getValue("legacy").shortSessionId)
        assertEquals(1, grouping.getValue("legacy").segmentCount)
        assertFalse(grouping.getValue("legacy").isMultiSegment)
    }

    private fun entry(id: String, sessionId: String?, segmentIndex: Int?) = RecordingEntry(
        id = id,
        fileName = "$id.mp4",
        metadata = RecordingMetadata(
            startedAtMillis = 1L,
            finishedAtMillis = 2L,
            duration = RecordingDuration(1L),
            width = 1_280,
            height = 720,
            audioEnabled = true,
            sessionId = sessionId,
            segmentIndex = segmentIndex,
        ),
        storage = RecordingStorageInfo(
            id = id,
            fileName = "$id.mp4",
            absolutePath = "/$id.mp4",
            sizeBytes = 1L,
            lastModifiedMillis = 1L,
        ),
    )
}
