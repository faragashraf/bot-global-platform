package com.ashraffarag.sentricam.recording.playback.android.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.core.view.isVisible
import com.ashraffarag.sentricam.R
import com.ashraffarag.sentricam.databinding.ViewVideoPlayerSurfaceBinding
import com.ashraffarag.sentricam.recording.domain.RecordingDurationFormatter
import com.ashraffarag.sentricam.recording.playback.domain.VideoPlayerUiState

class VideoPlayerSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val binding = ViewVideoPlayerSurfaceBinding.inflate(LayoutInflater.from(context), this)
    private var seeking = false
    private var onPlayPause: (() -> Unit)? = null
    private var onSeek: ((Int) -> Unit)? = null
    private var onFullScreen: (() -> Unit)? = null
    private val baseControlPadding = binding.playerControls.paddingLeft
    private var latestState = VideoPlayerUiState()
    private var compactFrame = false
    private var controlsVisible = true
    private val hideControls = Runnable {
        controlsVisible = false
        applyControlVisibility()
    }

    val videoView: FitVideoView
        get() = binding.videoView

    init {
        binding.videoView.setOnClickListener {
            if (latestState.isFullScreen) toggleControls() else onPlayPause?.invoke()
        }
        binding.centerPlayPauseButton.setOnClickListener {
            showControlsTemporarily()
            onPlayPause?.invoke()
        }
        binding.playPauseButton.setOnClickListener {
            showControlsTemporarily()
            onPlayPause?.invoke()
        }
        binding.fullScreenButton.setOnClickListener {
            showControlsTemporarily()
            onFullScreen?.invoke()
        }
        binding.playerSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.currentTime.text = RecordingDurationFormatter.format(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                seeking = true
                removeCallbacks(hideControls)
                controlsVisible = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seeking = false
                onSeek?.invoke(seekBar.progress)
                showControlsTemporarily()
            }
        })
    }

    fun setCallbacks(
        onPlayPause: () -> Unit,
        onSeek: (Int) -> Unit,
        onFullScreen: () -> Unit,
    ) {
        this.onPlayPause = onPlayPause
        this.onSeek = onSeek
        this.onFullScreen = onFullScreen
    }

    fun setVideoDimensions(widthPx: Int, heightPx: Int) {
        binding.videoView.setDisplayDimensions(widthPx, heightPx)
    }

    fun render(state: VideoPlayerUiState) {
        val enteredFullScreen = !latestState.isFullScreen && state.isFullScreen
        val startedPlaying = !latestState.isPlaying && state.isPlaying
        latestState = state
        val playLabel = if (state.isPlaying) R.string.library_pause else R.string.library_play
        val playIcon = if (state.isPlaying) R.drawable.ic_pause_24 else R.drawable.ic_play_24
        binding.playPauseButton.isEnabled = state.isPrepared
        binding.playPauseButton.setIconResource(playIcon)
        binding.playPauseButton.contentDescription = context.getString(playLabel)
        binding.centerPlayPauseButton.isEnabled = state.isPrepared
        binding.centerPlayPauseButton.setIconResource(playIcon)
        binding.centerPlayPauseButton.contentDescription = context.getString(playLabel)
        binding.playerLoading.isVisible = !state.isPrepared
        binding.fullScreenButton.isEnabled = state.isPrepared
        binding.fullScreenButton.setIconResource(
            if (state.isFullScreen) R.drawable.ic_fullscreen_exit_24 else R.drawable.ic_fullscreen_24,
        )
        binding.fullScreenButton.contentDescription = context.getString(
            if (state.isFullScreen) R.string.library_exit_full_screen else R.string.library_full_screen,
        )

        binding.playerSeek.max = state.durationMillis.coerceAtLeast(0)
        if (!seeking) {
            binding.playerSeek.progress = state.positionMillis.coerceIn(0, state.durationMillis.coerceAtLeast(0))
            binding.currentTime.text = RecordingDurationFormatter.format(state.positionMillis.toLong())
        }
        binding.totalTime.text = RecordingDurationFormatter.format(state.durationMillis.toLong())

        when {
            !state.isFullScreen || !state.isPlaying -> {
                removeCallbacks(hideControls)
                controlsVisible = true
            }

            enteredFullScreen || startedPlaying -> showControlsTemporarily()
        }
        applyControlVisibility()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val compactThreshold = (COMPACT_FRAME_HEIGHT_DP * resources.displayMetrics.density).toInt()
        val compact = height in 1 until compactThreshold
        if (compactFrame != compact) {
            compactFrame = compact
            render(latestState)
        }
    }

    fun setControlInsets(left: Int, right: Int, bottom: Int) {
        binding.playerControls.setPadding(
            baseControlPadding + left,
            binding.playerControls.paddingTop,
            baseControlPadding + right,
            baseControlPadding + bottom,
        )
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideControls)
        super.onDetachedFromWindow()
    }

    private fun toggleControls() {
        if (controlsVisible && latestState.isPlaying) {
            removeCallbacks(hideControls)
            controlsVisible = false
            applyControlVisibility()
        } else {
            showControlsTemporarily()
        }
    }

    private fun showControlsTemporarily() {
        removeCallbacks(hideControls)
        controlsVisible = true
        applyControlVisibility()
        if (latestState.isFullScreen && latestState.isPlaying && !seeking) {
            postDelayed(hideControls, CONTROLS_TIMEOUT_MILLIS)
        }
    }

    private fun applyControlVisibility() {
        binding.playerControls.isVisible = controlsVisible
        binding.centerPlayPauseButton.isVisible = controlsVisible && latestState.isPrepared &&
            !latestState.isPlaying && !compactFrame
    }

    private companion object {
        const val COMPACT_FRAME_HEIGHT_DP = 176
        const val CONTROLS_TIMEOUT_MILLIS = 3_000L
    }
}
