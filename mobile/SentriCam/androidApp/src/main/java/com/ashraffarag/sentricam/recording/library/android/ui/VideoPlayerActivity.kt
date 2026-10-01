package com.ashraffarag.sentricam.recording.library.android.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ashraffarag.sentricam.R
import com.ashraffarag.sentricam.databinding.ActivityVideoPlayerBinding
import com.ashraffarag.sentricam.recording.library.android.RecordingLibraryEngineFactory
import com.ashraffarag.sentricam.recording.library.capability.RecordingDeletionResult
import com.ashraffarag.sentricam.recording.library.capability.RecordingLibraryEngine
import com.ashraffarag.sentricam.recording.library.capability.RecordingRefreshResult
import com.ashraffarag.sentricam.recording.library.domain.RecordingCamera
import com.ashraffarag.sentricam.recording.library.domain.RecordingEntry
import com.ashraffarag.sentricam.recording.engine.domain.RecordingStartReason
import com.ashraffarag.sentricam.recording.engine.domain.StopReason
import com.ashraffarag.sentricam.recording.playback.android.AndroidVideoPlaybackController
import com.ashraffarag.sentricam.recording.playback.capability.VideoPlaybackController
import com.ashraffarag.sentricam.recording.playback.capability.VideoPlaybackListener
import com.ashraffarag.sentricam.recording.playback.domain.VideoPlayerUiState
import com.ashraffarag.sentricam.recording.playback.domain.VideoDisplayLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class VideoPlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVideoPlayerBinding
    private lateinit var engine: RecordingLibraryEngine
    private lateinit var playbackController: VideoPlaybackController
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestGeneration = AtomicInteger()
    private var recording: RecordingEntry? = null
    private var playerState = VideoPlayerUiState()
    private var systemBarInsets = Insets.NONE
    private var embeddedCardRadius = 0f
    private var videoDisplayWidthPx = 0
    private var videoDisplayHeightPx = 0
    private var technicalDetailsExpanded = false

    private val progressTick = object : Runnable {
        override fun run() {
            if (!playbackController.isPrepared) return
            playerState = playerState.withProgress(
                positionMillis = playbackController.currentPositionMillis,
                playing = playbackController.isPlaying,
            )
            binding.videoPlayerSurface.render(playerState)
            if (playbackController.isPlaying) {
                mainHandler.postDelayed(this, PROGRESS_INTERVAL_MILLIS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        playerState = VideoPlayerUiState.restored(
            positionMillis = savedInstanceState?.getInt(STATE_POSITION_MILLIS) ?: 0,
            playWhenReady = savedInstanceState?.getBoolean(STATE_PLAY_WHEN_READY) ?: false,
            isFullScreen = savedInstanceState?.getBoolean(STATE_FULL_SCREEN) ?: false,
        )
        technicalDetailsExpanded = savedInstanceState?.getBoolean(STATE_TECHNICAL_DETAILS) ?: false
        embeddedCardRadius = binding.videoPlayerCard.radius
        engine = RecordingLibraryEngineFactory.create(applicationContext)
        playbackController = AndroidVideoPlaybackController(
            videoView = binding.videoPlayerSurface.videoView,
            listener = playbackListener,
        )

        configureInsets()
        configureControls()
        configureBackNavigation()
        binding.playerRoot.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPlayerCardConstraints(playerState.isFullScreen)
        }
        binding.playerToolbar.setNavigationOnClickListener { handleBack() }
        binding.detailsToggle.setOnClickListener { toggleTechnicalDetails() }
        binding.deleteButton.setOnClickListener { confirmDeletion() }
        binding.retryPlayerButton.setOnClickListener { loadRecording() }
        renderFullScreenMode()
        loadRecording()
    }

    override fun onResume() {
        super.onResume()
        if (playerState.playWhenReady && playbackController.isPrepared) {
            playbackController.play()
            playerState = playerState.withProgress(
                playbackController.currentPositionMillis,
                playbackController.isPlaying,
            )
            scheduleProgress()
            binding.videoPlayerSurface.render(playerState)
        }
    }

    override fun onPause() {
        mainHandler.removeCallbacks(progressTick)
        if (playbackController.isPrepared) {
            playerState = playerState.withProgress(
                playbackController.currentPositionMillis,
                playbackController.isPlaying,
            )
            playbackController.pause()
            playerState = playerState.withProgress(playerState.positionMillis, false)
            binding.videoPlayerSurface.render(playerState)
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (playbackController.isPrepared) {
            playerState = playerState.withProgress(
                playbackController.currentPositionMillis,
                playbackController.isPlaying,
            )
        }
        outState.putInt(STATE_POSITION_MILLIS, playerState.positionMillis)
        outState.putBoolean(STATE_PLAY_WHEN_READY, playerState.playWhenReady)
        outState.putBoolean(STATE_FULL_SCREEN, playerState.isFullScreen)
        outState.putBoolean(STATE_TECHNICAL_DETAILS, technicalDetailsExpanded)
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.playerRoot.post {
            applyPlayerCardConstraints(playerState.isFullScreen)
        }
    }

    override fun onDestroy() {
        requestGeneration.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        playbackController.release()
        super.onDestroy()
    }

    private val playbackListener = object : VideoPlaybackListener {
        override fun onPrepared(durationMillis: Int, isPlaying: Boolean) {
            playerState = playerState.prepared(durationMillis).withProgress(
                positionMillis = playbackController.currentPositionMillis,
                playing = isPlaying,
            )
            if (playerState.playWhenReady && lifecycle.currentState.isAtLeast(
                    androidx.lifecycle.Lifecycle.State.RESUMED,
                )
            ) {
                playbackController.play()
                playerState = playerState.withProgress(
                    playbackController.currentPositionMillis,
                    playbackController.isPlaying,
                )
                scheduleProgress()
            }
            binding.videoPlayerSurface.render(playerState)
        }

        override fun onCompletion() {
            mainHandler.removeCallbacks(progressTick)
            playerState = playerState.completed()
            binding.videoPlayerSurface.render(playerState)
        }

        override fun onError() {
            showPlayerError()
        }
    }

    private fun configureInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.playerRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            systemBarInsets = Insets.max(bars, cutout)
            applySystemBarInsets()
            insets
        }
    }

    private fun configureControls() {
        binding.videoPlayerSurface.setCallbacks(
            onPlayPause = ::togglePlayback,
            onSeek = { positionMillis ->
                playbackController.seekTo(positionMillis)
                playerState = playerState.withProgress(positionMillis, playbackController.isPlaying)
                binding.videoPlayerSurface.render(playerState)
                scheduleProgress()
            },
            onFullScreen = { setFullScreen(!playerState.isFullScreen) },
        )
    }

    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })
    }

    private fun handleBack() {
        if (playerState.isFullScreen) {
            setFullScreen(false)
        } else {
            finish()
        }
    }

    private fun loadRecording() {
        val recordingId = intent.getStringExtra(EXTRA_RECORDING_ID)
        if (recordingId.isNullOrBlank()) {
            showPlayerError()
            return
        }
        showLoading()
        val generation = requestGeneration.incrementAndGet()
        executor.execute {
            val refreshResult = engine.refresh()
            val entry = if (refreshResult is RecordingRefreshResult.Loaded) {
                engine.find(recordingId)
            } else {
                null
            }
            mainHandler.post {
                if (generation != requestGeneration.get()) return@post
                if (entry == null) showPlayerError() else bindRecording(entry)
            }
        }
    }

    private fun bindRecording(entry: RecordingEntry) {
        recording = entry
        binding.loadingIndicator.visibility = View.GONE
        binding.playerErrorGroup.visibility = View.GONE
        binding.videoPlayerCard.visibility = View.VISIBLE
        binding.detailsScroll.visibility = if (playerState.isFullScreen) View.GONE else View.VISIBLE
        binding.playerToolbar.setTitle(R.string.library_player_title)

        binding.recordingStarted.setValue(RecordingLibraryUiFormatter.dateTime(entry.metadata.startedAtMillis))
        binding.recordingFinished.setValue(RecordingLibraryUiFormatter.dateTime(entry.metadata.finishedAtMillis))
        val displayDimensions = entry.metadata.displayDimensions
        videoDisplayWidthPx = displayDimensions.width
        videoDisplayHeightPx = displayDimensions.height
        binding.videoPlayerSurface.setVideoDimensions(
            displayDimensions.width,
            displayDimensions.height,
        )
        binding.detailResolution.setValue(
            RecordingLibraryUiFormatter.resolution(displayDimensions.width, displayDimensions.height),
        )
        binding.detailDuration.setValue(RecordingLibraryUiFormatter.duration(entry.metadata.duration))
        binding.detailFileSize.setValue(RecordingLibraryUiFormatter.fileSize(entry.storage.sizeBytes))
        binding.detailStoragePath.setValue(entry.storage.absolutePath)
        binding.detailAudio.setValue(
            getString(if (entry.metadata.audioEnabled) R.string.library_enabled else R.string.library_disabled),
        )
        binding.detailCamera.setValue(
            getString(
                when (entry.metadata.camera) {
                    RecordingCamera.REAR -> R.string.library_camera_rear
                    RecordingCamera.FRONT -> R.string.library_camera_front
                    RecordingCamera.UNKNOWN -> R.string.library_value_unknown
                },
            ),
        )
        binding.detailTimestamp.setValue(
            getString(
                when (entry.metadata.timestampOverlayEnabled) {
                    true -> R.string.library_enabled
                    false -> R.string.library_disabled
                    null -> R.string.library_value_unknown
                },
            ),
        )
        binding.detailTrigger.setValue(
            getString(
                when (entry.metadata.startReason) {
                    RecordingStartReason.MOTION -> R.string.library_trigger_motion_label
                    RecordingStartReason.MANUAL -> R.string.library_trigger_manual_label
                    RecordingStartReason.REMOTE -> R.string.library_trigger_remote_label
                    null -> R.string.library_value_unknown
                },
            ),
        )
        val hasMotionMetadata = entry.metadata.startReason == RecordingStartReason.MOTION
        binding.detailMotionEvent.visibility = if (hasMotionMetadata) View.VISIBLE else View.GONE
        binding.detailMotionDetected.visibility = if (hasMotionMetadata) View.VISIBLE else View.GONE
        binding.detailMotionRecordingStarted.visibility = if (hasMotionMetadata) View.VISIBLE else View.GONE
        binding.detailLastMotion.visibility = if (hasMotionMetadata) View.VISIBLE else View.GONE
        binding.detailMotionEnded.visibility = if (hasMotionMetadata) View.VISIBLE else View.GONE
        binding.detailMotionStats.visibility = if (hasMotionMetadata) View.VISIBLE else View.GONE
        if (hasMotionMetadata) {
            binding.detailMotionEvent.setValue(entry.metadata.motionEventId ?: getString(R.string.library_value_unknown))
            binding.detailMotionDetected.setValue(entry.metadata.motionDetectedAtMillis.asDisplayTime())
            binding.detailMotionRecordingStarted.setValue(
                entry.metadata.motionRecordingStartedAtMillis.asDisplayTime(),
            )
            binding.detailLastMotion.setValue(entry.metadata.lastMotionAtMillis.asDisplayTime())
            binding.detailMotionEnded.setValue(entry.metadata.motionEndedAtMillis.asDisplayTime())
            binding.detailMotionStats.setValue(
                getString(
                    R.string.library_motion_stats_value,
                    entry.metadata.motionSensitivity ?: getString(R.string.library_value_unknown),
                    entry.metadata.peakMotionScore ?: 0.0,
                    entry.metadata.motionBurstCount ?: 0,
                ),
            )
        }
        binding.detailStopReason.setValue(
            getString(
                when (entry.metadata.stopReason) {
                    StopReason.USER -> R.string.library_stop_user
                    StopReason.MOTION_ENDED -> R.string.library_stop_motion_ended
                    StopReason.REMOTE -> R.string.library_stop_remote
                    StopReason.LIFECYCLE -> R.string.library_stop_lifecycle
                    StopReason.STORAGE_LIMIT -> R.string.library_stop_storage
                    StopReason.CAMERA_UNAVAILABLE -> R.string.library_stop_camera
                    StopReason.SEGMENT_FAILURE -> R.string.library_stop_segment_failure
                    StopReason.RELEASE -> R.string.library_stop_release
                    null -> R.string.library_value_unknown
                },
            ),
        )
        renderTechnicalDetails()
        binding.videoPlayerSurface.render(playerState)
        playbackController.load(
            path = entry.storage.absolutePath,
            initialPositionMillis = playerState.positionMillis,
            playWhenReady = playerState.playWhenReady && lifecycle.currentState.isAtLeast(
                androidx.lifecycle.Lifecycle.State.RESUMED,
            ),
        )
        applyPlayerCardConstraints(playerState.isFullScreen)
    }

    private fun Long?.asDisplayTime(): String = this?.let(RecordingLibraryUiFormatter::dateTime)
        ?: getString(R.string.library_value_unknown)

    private fun toggleTechnicalDetails() {
        technicalDetailsExpanded = !technicalDetailsExpanded
        renderTechnicalDetails()
    }

    private fun renderTechnicalDetails() {
        binding.technicalDetails.visibility = if (technicalDetailsExpanded) View.VISIBLE else View.GONE
        binding.detailsToggle.setText(
            if (technicalDetailsExpanded) {
                R.string.library_hide_technical_details
            } else {
                R.string.library_show_technical_details
            },
        )
    }

    private fun togglePlayback() {
        if (!playbackController.isPrepared) return
        val shouldPlay = !playbackController.isPlaying
        playerState = playerState.withPlaybackRequest(shouldPlay)
        if (shouldPlay) {
            playbackController.play()
            scheduleProgress()
        } else {
            playbackController.pause()
            mainHandler.removeCallbacks(progressTick)
        }
        playerState = playerState.withProgress(
            playbackController.currentPositionMillis,
            playbackController.isPlaying,
        ).copy(playWhenReady = shouldPlay)
        binding.videoPlayerSurface.render(playerState)
    }

    private fun setFullScreen(enabled: Boolean) {
        if (playerState.isFullScreen == enabled) return
        playerState = if (enabled) playerState.enterFullScreen() else playerState.exitFullScreen()
        renderFullScreenMode()
    }

    private fun renderFullScreenMode() {
        val fullScreen = playerState.isFullScreen
        binding.playerToolbar.visibility = if (fullScreen) View.GONE else View.VISIBLE
        binding.detailsScroll.visibility = if (!fullScreen && recording != null) View.VISIBLE else View.GONE
        binding.videoPlayerCard.radius = if (fullScreen) 0f else embeddedCardRadius
        applyPlayerCardConstraints(fullScreen)

        WindowInsetsControllerCompat(window, binding.playerRoot).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullScreen) hide(WindowInsetsCompat.Type.systemBars())
            else show(WindowInsetsCompat.Type.systemBars())
        }
        applySystemBarInsets()
        binding.videoPlayerSurface.render(playerState)
        if (fullScreen) binding.videoPlayerCard.bringToFront()
        ViewCompat.requestApplyInsets(binding.playerRoot)
        binding.playerRoot.post { applyPlayerCardConstraints(playerState.isFullScreen) }
    }

    private fun applyPlayerCardConstraints(fullScreen: Boolean) {
        val params = binding.videoPlayerCard.layoutParams as ConstraintLayout.LayoutParams
        if (fullScreen) {
            params.width = 0
            params.height = 0
            params.marginStart = 0
            params.marginEnd = 0
            params.topMargin = 0
            params.bottomMargin = 0
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            params.topToBottom = ConstraintLayout.LayoutParams.UNSET
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        } else {
            val margin = resources.getDimensionPixelSize(R.dimen.library_content_padding)
            val availableWidth = (
                binding.playerRoot.width - binding.playerRoot.paddingLeft -
                    binding.playerRoot.paddingRight - (margin * 2)
                ).coerceAtLeast(1)
            val viewportHeight = (
                binding.playerRoot.height - binding.playerToolbar.height -
                    binding.playerRoot.paddingTop - binding.playerRoot.paddingBottom
                ).coerceAtLeast(1)
            val frame = VideoDisplayLayout.embeddedFrame(
                availableWidthPx = availableWidth,
                viewportHeightPx = viewportHeight,
                videoWidthPx = videoDisplayWidthPx,
                videoHeightPx = videoDisplayHeightPx,
                density = resources.displayMetrics.density,
            )
            params.width = frame.widthPx
            params.height = frame.heightPx
            params.marginStart = margin
            params.marginEnd = margin
            params.topMargin = 0
            params.bottomMargin = 0
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            params.topToTop = ConstraintLayout.LayoutParams.UNSET
            params.topToBottom = R.id.player_toolbar
            params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
        }
        binding.videoPlayerCard.layoutParams = params
    }

    private fun applySystemBarInsets() {
        if (playerState.isFullScreen) {
            binding.playerRoot.setPadding(0, 0, 0, 0)
            binding.videoPlayerSurface.setControlInsets(
                left = systemBarInsets.left,
                right = systemBarInsets.right,
                bottom = systemBarInsets.bottom,
            )
        } else {
            binding.playerRoot.setPadding(
                systemBarInsets.left,
                systemBarInsets.top,
                systemBarInsets.right,
                systemBarInsets.bottom,
            )
            binding.videoPlayerSurface.setControlInsets(0, 0, 0)
        }
    }

    private fun scheduleProgress() {
        mainHandler.removeCallbacks(progressTick)
        mainHandler.post(progressTick)
    }

    private fun confirmDeletion() {
        val entry = recording ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.library_delete_title)
            .setMessage(R.string.library_delete_message)
            .setNegativeButton(R.string.library_cancel, null)
            .setPositiveButton(R.string.library_delete) { _, _ -> deleteRecording(entry) }
            .show()
    }

    private fun deleteRecording(entry: RecordingEntry, force: Boolean = false) {
        binding.deleteButton.isEnabled = false
        val generation = requestGeneration.incrementAndGet()
        executor.execute {
            val result = engine.delete(entry.id, force)
            mainHandler.post {
                if (generation != requestGeneration.get()) return@post
                when (result) {
                    RecordingDeletionResult.Deleted -> {
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                    RecordingDeletionResult.BlockedPendingUpload -> confirmDeletionBeforeUpload(entry)
                    else -> {
                        binding.deleteButton.isEnabled = true
                        Toast.makeText(this, R.string.library_delete_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun confirmDeletionBeforeUpload(entry: RecordingEntry) {
        binding.deleteButton.isEnabled = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.library_delete_pending_upload_title)
            .setMessage(R.string.library_delete_pending_upload_message)
            .setNegativeButton(R.string.library_cancel, null)
            .setPositiveButton(R.string.library_delete_anyway) { _, _ -> deleteRecording(entry, force = true) }
            .show()
    }

    private fun showLoading() {
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.videoPlayerCard.visibility = View.GONE
        binding.detailsScroll.visibility = View.GONE
        binding.playerErrorGroup.visibility = View.GONE
    }

    private fun showPlayerError() {
        mainHandler.removeCallbacks(progressTick)
        playerState = playerState.copy(isPrepared = false, isPlaying = false, playWhenReady = false)
        if (playerState.isFullScreen) {
            playerState = playerState.exitFullScreen()
            renderFullScreenMode()
        }
        binding.loadingIndicator.visibility = View.GONE
        binding.videoPlayerCard.visibility = View.GONE
        binding.detailsScroll.visibility = View.GONE
        binding.playerErrorGroup.visibility = View.VISIBLE
    }

    companion object {
        private const val EXTRA_RECORDING_ID = "recording_id"
        private const val STATE_POSITION_MILLIS = "playback_position_millis"
        private const val STATE_PLAY_WHEN_READY = "play_when_ready"
        private const val STATE_FULL_SCREEN = "full_screen"
        private const val STATE_TECHNICAL_DETAILS = "technical_details"
        private const val PROGRESS_INTERVAL_MILLIS = 500L

        fun createIntent(context: Context, recordingId: String): Intent =
            Intent(context, VideoPlayerActivity::class.java)
                .putExtra(EXTRA_RECORDING_ID, recordingId)
    }
}
