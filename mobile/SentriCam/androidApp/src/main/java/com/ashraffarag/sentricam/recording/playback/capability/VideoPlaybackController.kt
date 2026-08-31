package com.ashraffarag.sentricam.recording.playback.capability

interface VideoPlaybackController {
    val isPrepared: Boolean
    val isPlaying: Boolean
    val currentPositionMillis: Int
    val durationMillis: Int

    fun load(path: String, initialPositionMillis: Int, playWhenReady: Boolean)

    fun play()

    fun pause()

    fun seekTo(positionMillis: Int)

    fun release()
}

interface VideoPlaybackListener {
    fun onPrepared(durationMillis: Int, isPlaying: Boolean)

    fun onCompletion()

    fun onError()
}
