package com.ashraffarag.sentricam.recording.playback.domain

data class VideoPlayerUiState(
    val isPrepared: Boolean = false,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val positionMillis: Int = 0,
    val durationMillis: Int = 0,
    val isFullScreen: Boolean = false,
) {
    fun prepared(durationMillis: Int): VideoPlayerUiState = copy(
        isPrepared = true,
        durationMillis = durationMillis.coerceAtLeast(0),
        positionMillis = positionMillis.coerceIn(0, durationMillis.coerceAtLeast(0)),
    )

    fun withProgress(positionMillis: Int, playing: Boolean): VideoPlayerUiState = copy(
        positionMillis = positionMillis.coerceIn(0, durationMillis.coerceAtLeast(0)),
        isPlaying = playing,
    )

    fun withPlaybackRequest(shouldPlay: Boolean): VideoPlayerUiState = copy(
        playWhenReady = shouldPlay,
        isPlaying = isPrepared && shouldPlay,
    )

    fun enterFullScreen(): VideoPlayerUiState = copy(isFullScreen = true)

    fun exitFullScreen(): VideoPlayerUiState = copy(isFullScreen = false)

    fun completed(): VideoPlayerUiState = copy(
        isPlaying = false,
        playWhenReady = false,
        positionMillis = durationMillis,
    )

    companion object {
        fun restored(
            positionMillis: Int,
            playWhenReady: Boolean,
            isFullScreen: Boolean,
        ): VideoPlayerUiState = VideoPlayerUiState(
            positionMillis = positionMillis.coerceAtLeast(0),
            playWhenReady = playWhenReady,
            isFullScreen = isFullScreen,
        )
    }
}
