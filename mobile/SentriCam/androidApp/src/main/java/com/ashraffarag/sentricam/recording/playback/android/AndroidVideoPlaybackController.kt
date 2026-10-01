package com.ashraffarag.sentricam.recording.playback.android

import android.util.Log
import android.widget.VideoView
import com.ashraffarag.sentricam.recording.playback.capability.VideoPlaybackController
import com.ashraffarag.sentricam.recording.playback.capability.VideoPlaybackListener
import java.io.File

class AndroidVideoPlaybackController(
    private val videoView: VideoView,
    private val listener: VideoPlaybackListener,
) : VideoPlaybackController {
    override var isPrepared: Boolean = false
        private set

    private var released = false

    override val isPlaying: Boolean
        get() = isPrepared && !released && runCatching { videoView.isPlaying }.getOrDefault(false)

    override val currentPositionMillis: Int
        get() = if (isPrepared && !released) {
            runCatching { videoView.currentPosition.coerceAtLeast(0) }.getOrDefault(0)
        } else {
            0
        }

    override val durationMillis: Int
        get() = if (isPrepared && !released) {
            runCatching { videoView.duration.coerceAtLeast(0) }.getOrDefault(0)
        } else {
            0
        }

    init {
        videoView.setOnPreparedListener { mediaPlayer ->
            if (released) return@setOnPreparedListener
            isPrepared = true
            val duration = mediaPlayer.duration.coerceAtLeast(0)
            listener.onPrepared(duration, videoView.isPlaying)
        }
        videoView.setOnCompletionListener {
            if (!released) listener.onCompletion()
        }
        videoView.setOnErrorListener { _, what, extra ->
            if (!released) {
                Log.e(TAG, "Video playback failed: what=$what extra=$extra")
                isPrepared = false
                listener.onError()
            }
            true
        }
    }

    override fun load(path: String, initialPositionMillis: Int, playWhenReady: Boolean) {
        if (released) return
        val source = File(path)
        if (!source.isFile || !source.canRead()) {
            Log.e(TAG, "Video source is missing or unreadable")
            listener.onError()
            return
        }
        isPrepared = false
        videoView.setOnPreparedListener { mediaPlayer ->
            if (released) return@setOnPreparedListener
            isPrepared = true
            val duration = mediaPlayer.duration.coerceAtLeast(0)
            val target = initialPositionMillis.coerceIn(0, duration)
            if (target > 0) videoView.seekTo(target) else videoView.seekTo(FIRST_FRAME_MILLIS)
            if (playWhenReady) videoView.start()
            listener.onPrepared(duration, videoView.isPlaying)
        }
        videoView.setVideoPath(source.absolutePath)
        videoView.requestFocus()
    }

    override fun play() {
        if (!isPrepared || released) return
        if (durationMillis > 0 && currentPositionMillis >= durationMillis) videoView.seekTo(0)
        videoView.start()
    }

    override fun pause() {
        if (isPlaying) videoView.pause()
    }

    override fun seekTo(positionMillis: Int) {
        if (!isPrepared || released) return
        videoView.seekTo(positionMillis.coerceIn(0, durationMillis))
    }

    override fun release() {
        if (released) return
        released = true
        isPrepared = false
        runCatching { videoView.stopPlayback() }
        videoView.setOnPreparedListener(null)
        videoView.setOnCompletionListener(null)
        videoView.setOnErrorListener(null)
    }

    private companion object {
        const val TAG = "SentriCamPlayback"
        const val FIRST_FRAME_MILLIS = 1
    }
}
