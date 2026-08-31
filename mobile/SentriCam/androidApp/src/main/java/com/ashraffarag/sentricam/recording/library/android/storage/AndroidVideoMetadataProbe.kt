package com.ashraffarag.sentricam.recording.library.android.storage

import android.media.MediaMetadataRetriever
import com.ashraffarag.sentricam.camera.domain.OutputRotation

class AndroidVideoMetadataProbe {
    fun probe(absolutePath: String): VideoTrackMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(absolutePath)
            VideoTrackMetadata(
                encodedWidth = retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                encodedHeight = retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                rotationDegrees = OutputRotation.fromDegrees(
                    retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
                ).degrees,
                durationMillis = retriever.longMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION,
                ).coerceAtLeast(0L),
                hasAudio = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO,
                ).equals("yes", ignoreCase = true),
            )
        } finally {
            retriever.release()
        }
    }

    private fun MediaMetadataRetriever.longMetadata(key: Int): Long =
        extractMetadata(key)?.toLongOrNull() ?: 0L

    private fun MediaMetadataRetriever.intMetadata(key: Int): Int =
        extractMetadata(key)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
}

data class VideoTrackMetadata(
    val encodedWidth: Int,
    val encodedHeight: Int,
    val rotationDegrees: Int,
    val durationMillis: Long,
    val hasAudio: Boolean,
)
