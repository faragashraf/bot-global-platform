package com.ashraffarag.sentricam.recording.library.android.thumbnail

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.scale
import com.ashraffarag.sentricam.recording.library.domain.RecordingEntry
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class RecordingThumbnailLoader : AutoCloseable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val cache = object : LruCache<String, Bitmap>(CACHE_SIZE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(recording: RecordingEntry, callback: (Bitmap?) -> Unit) {
        if (closed.get()) return
        cache.get(recording.id)?.let { cached ->
            callback(cached)
            return
        }
        executor.execute {
            val thumbnail = createThumbnail(recording)
            if (thumbnail != null) cache.put(recording.id, thumbnail)
            if (!closed.get()) {
                mainHandler.post {
                    if (!closed.get()) callback(thumbnail)
                }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        cache.evictAll()
    }

    private fun createThumbnail(recording: RecordingEntry): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(recording.storage.absolutePath)
            val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            val scale = (MAX_THUMBNAIL_WIDTH.toFloat() / frame.width).coerceAtMost(1f)
            if (scale >= 1f) {
                frame
            } else {
                frame.scale(
                    MAX_THUMBNAIL_WIDTH,
                    (frame.height * scale).toInt().coerceAtLeast(1),
                ).also { scaled ->
                    if (scaled !== frame) frame.recycle()
                }
            }
        } catch (failure: Throwable) {
            Log.w(TAG, "Unable to load thumbnail for ${recording.fileName}", failure)
            null
        } finally {
            try {
                retriever.release()
            } catch (failure: Throwable) {
                Log.w(TAG, "Unable to release thumbnail reader", failure)
            }
        }
    }

    private companion object {
        const val TAG = "RecordingThumbnail"
        const val MAX_THUMBNAIL_WIDTH = 480
        const val CACHE_SIZE_BYTES = 8 * 1024 * 1024
    }
}
