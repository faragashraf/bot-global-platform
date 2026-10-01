package com.ashraffarag.sentricam.recording.engine.android

import android.media.MediaMetadataRetriever
import com.ashraffarag.sentricam.recording.engine.capability.FinalizedSegmentOutput
import com.ashraffarag.sentricam.recording.engine.domain.RecordingQuality
import java.io.File

class AndroidVideoMetadataProbe {
    fun probe(path: String, fallbackDurationMillis: Long, fallbackAudioEnabled: Boolean): FinalizedSegmentOutput {
        val file = File(path)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val width = retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rotation = retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val duration = retriever.longMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                .takeIf { it > 0L } ?: fallbackDurationMillis.coerceAtLeast(0L)
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                ?.equals("yes", ignoreCase = true) ?: fallbackAudioEnabled
            FinalizedSegmentOutput(
                durationMillis = duration,
                fileSizeBytes = file.length().coerceAtLeast(0L),
                width = width.coerceAtLeast(0),
                height = height.coerceAtLeast(0),
                rotationDegrees = normalizeRotation(rotation),
                actualQuality = qualityFor(width, height),
                audioEnabled = hasAudio,
                finalized = file.isFile && file.length() > 0L,
            )
        } finally {
            retriever.release()
        }
    }

    private fun MediaMetadataRetriever.intMetadata(key: Int): Int =
        extractMetadata(key)?.toIntOrNull() ?: 0

    private fun MediaMetadataRetriever.longMetadata(key: Int): Long =
        extractMetadata(key)?.toLongOrNull() ?: 0L

    private fun qualityFor(width: Int, height: Int): RecordingQuality {
        val longestEdge = maxOf(width, height)
        return when {
            longestEdge >= FULL_HD_LONG_EDGE -> RecordingQuality.FULL_HD
            longestEdge >= HD_LONG_EDGE -> RecordingQuality.HD
            else -> RecordingQuality.SD
        }
    }

    private fun normalizeRotation(rotation: Int): Int = ((rotation % 360) + 360) % 360

    private companion object {
        const val HD_LONG_EDGE = 1_280
        const val FULL_HD_LONG_EDGE = 1_920
    }
}
