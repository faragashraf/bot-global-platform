package com.ashraffarag.sentricam.motion.capability

import com.ashraffarag.sentricam.motion.domain.FrameDifferenceMotionAnalyzer
import com.ashraffarag.sentricam.motion.domain.MotionDebugMetrics
import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig
import com.ashraffarag.sentricam.motion.domain.MotionDetectionState
import com.ashraffarag.sentricam.motion.domain.MotionEvent
import com.ashraffarag.sentricam.motion.domain.MotionEventSummary
import com.ashraffarag.sentricam.motion.domain.MotionFailure
import com.ashraffarag.sentricam.motion.domain.MotionFailureCode
import com.ashraffarag.sentricam.motion.domain.MotionFrame
import com.ashraffarag.sentricam.motion.domain.MotionRecoveryAction
import com.ashraffarag.sentricam.motion.domain.MotionStartResult
import com.ashraffarag.sentricam.motion.domain.MotionStopResult
import com.ashraffarag.sentricam.motion.domain.MotionUpdateResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class DefaultMotionDetectionEngine(
    private val analyzer: FrameDifferenceMotionAnalyzer,
    private val clock: MotionClock,
    private val idFactory: MotionIdFactory,
    private val scheduler: MotionScheduler,
) : MotionDetectionEngine {
    private val lock = Any()
    private val mutableState = MutableStateFlow<MotionDetectionState>(MotionDetectionState.Disabled)
    private val mutableEvents = MutableSharedFlow<MotionEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    override val state = mutableState.asStateFlow()
    override val events = mutableEvents.asSharedFlow()

    private var config = MotionDetectionConfig()
    private var running = false
    private var currentGeneration = 0L
    override val generation: Long
        get() = synchronized(lock) { currentGeneration }
    private var warmupRemaining = 0
    private var positiveFrames = 0
    private var lastProcessedTimestamp = Long.MIN_VALUE
    private var processedFrames = 0L
    private var droppedFrames = 0L
    private var processingTotalNanos = 0L
    private var firstProcessedAtMillis = 0L
    private var candidateToken = 0L
    private var confirmationTask: MotionScheduledTask? = null
    private var holdTask: MotionScheduledTask? = null
    private var cooldownTask: MotionScheduledTask? = null
    private var activeEvent: MutableEvent? = null

    override suspend fun start(config: MotionDetectionConfig): MotionStartResult = synchronized(lock) {
        config.validationFailure()?.let { return@synchronized MotionStartResult.Rejected(it) }
        if (running) return@synchronized MotionStartResult.AlreadyRunning
        if (!config.enabled) return@synchronized MotionStartResult.Rejected(
            MotionFailure.invalidConfig("monitoring_disabled"),
        )
        this.config = config
        running = true
        beginWarmupLocked()
        MotionStartResult.Started
    }

    override suspend fun stop(): MotionStopResult = synchronized(lock) {
        if (!running) return@synchronized MotionStopResult.NotRunning
        running = false
        currentGeneration++
        cancelTasksLocked()
        analyzer.reset()
        emitEndedIfActiveLocked(clock.nowMillis())
        activeEvent = null
        positiveFrames = 0
        mutableState.value = MotionDetectionState.Disabled
        MotionStopResult.Stopped
    }

    override suspend fun updateConfig(config: MotionDetectionConfig): MotionUpdateResult = synchronized(lock) {
        config.validationFailure()?.let { return@synchronized MotionUpdateResult.Rejected(it) }
        val previousEffectiveProfile = this.config.resolvedSensitivity().effectiveProfile
        this.config = config
        if (!config.enabled) {
            running = false
            currentGeneration++
            cancelTasksLocked()
            analyzer.reset()
            emitEndedIfActiveLocked(clock.nowMillis())
            activeEvent = null
            mutableState.value = MotionDetectionState.Disabled
        } else if (running && previousEffectiveProfile != config.resolvedSensitivity().effectiveProfile) {
            beginWarmupLocked(preserveActiveEvent = activeEvent != null)
        }
        MotionUpdateResult.Updated
    }

    override fun submitFrame(frame: MotionFrame) {
        synchronized(lock) {
            if (!running || frame.generation != currentGeneration) return
            if (lastProcessedTimestamp != Long.MIN_VALUE &&
                frame.timestampMillis - lastProcessedTimestamp < config.frameIntervalMillis
            ) {
                droppedFrames++
                refreshMetricsLocked()
                return
            }
            lastProcessedTimestamp = frame.timestampMillis
            val startedNanos = System.nanoTime()
            try {
                val profile = config.resolvedSensitivity().effectiveProfile
                val analysis = analyzer.analyze(frame, profile.noiseTolerance)
                processingTotalNanos += (System.nanoTime() - startedNanos).coerceAtLeast(0L)
                processedFrames++
                if (firstProcessedAtMillis == 0L) firstProcessedAtMillis = frame.timestampMillis
                val metrics = metricsLocked(analysis.safeScore)
                if (warmupRemaining > 0 || !analysis.hasBaseline) {
                    if (warmupRemaining > 0) warmupRemaining--
                    mutableState.value = MotionDetectionState.Initializing(
                        currentGeneration,
                        warmupRemaining,
                        metrics,
                    )
                    if (warmupRemaining == 0 && analysis.hasBaseline) {
                        val event = activeEvent
                        mutableState.value = if (event == null) {
                            MotionDetectionState.NoMotion(metrics)
                        } else {
                            MotionDetectionState.MotionConfirmed(event.summary(config.sensitivity), metrics)
                        }
                    }
                    return
                }
                if (mutableState.value is MotionDetectionState.Initializing) {
                    val event = activeEvent
                    mutableState.value = if (event == null) {
                        MotionDetectionState.NoMotion(metrics)
                    } else {
                        MotionDetectionState.MotionConfirmed(event.summary(config.sensitivity), metrics)
                    }
                    return
                }
                handleAnalysisLocked(analysis, frame.timestampMillis, metrics)
            } catch (failure: Throwable) {
                failLocked(
                    MotionFailure(
                        MotionFailureCode.PROCESSING_FAILED,
                        retryAllowed = true,
                        recoveryAction = MotionRecoveryAction.RETRY,
                        diagnosticTag = "frame_processing",
                        technicalCause = failure,
                    ),
                )
            }
        }
    }

    override fun resetForCameraChange(): Long = synchronized(lock) {
        currentGeneration++
        if (running) beginWarmupLocked(incrementGeneration = false, preserveActiveEvent = true)
        currentGeneration
    }

    override fun simulateMotion(timestampMillis: Long) {
        synchronized(lock) {
            if (!running) return
            positiveFrames = config.resolvedSensitivity().effectiveProfile.requiredPositiveFrames
            confirmMotionLocked(timestampMillis, score = 1.0)
        }
    }

    override fun reportAnalyzerFailure(failure: Throwable, unsupportedCombination: Boolean) {
        synchronized(lock) {
            if (!running) return
            failLocked(
                MotionFailure(
                    code = if (unsupportedCombination) {
                        MotionFailureCode.UNSUPPORTED_USE_CASE_COMBINATION
                    } else {
                        MotionFailureCode.ANALYZER_UNAVAILABLE
                    },
                    retryAllowed = true,
                    recoveryAction = MotionRecoveryAction.RESTART_CAMERA,
                    diagnosticTag = "camera_analysis",
                    technicalCause = failure,
                ),
            )
        }
    }

    private fun beginWarmupLocked(
        incrementGeneration: Boolean = true,
        preserveActiveEvent: Boolean = false,
    ) {
        if (incrementGeneration) currentGeneration++
        cancelTasksLocked()
        analyzer.reset()
        if (!preserveActiveEvent) activeEvent = null
        warmupRemaining = config.warmupFrameCount
        positiveFrames = 0
        lastProcessedTimestamp = Long.MIN_VALUE
        processedFrames = 0L
        droppedFrames = 0L
        processingTotalNanos = 0L
        firstProcessedAtMillis = 0L
        mutableState.value = MotionDetectionState.Initializing(currentGeneration, warmupRemaining)
    }

    private fun handleAnalysisLocked(
        analysis: com.ashraffarag.sentricam.motion.domain.MotionAnalysis,
        timestampMillis: Long,
        baseMetrics: MotionDebugMetrics,
    ) {
        val score = analysis.safeScore
        val profile = config.resolvedSensitivity().effectiveProfile
        val globalChangeDominates = kotlin.math.abs(analysis.globalBrightnessDelta) >
            profile.brightnessChangeTolerance &&
            analysis.changedPixelRatio < maxOf(profile.changedAreaThreshold * 2.0, 0.10)
        val positive = score >= profile.threshold &&
            analysis.changedPixelRatio >= profile.changedAreaThreshold &&
            !globalChangeDominates
        when (val current = mutableState.value) {
            is MotionDetectionState.MotionConfirmed -> {
                if (positive) updateActiveEventLocked(score, timestampMillis, baseMetrics)
                else beginHoldingLocked(timestampMillis, baseMetrics)
            }
            is MotionDetectionState.Holding -> {
                if (positive) {
                    holdTask?.cancel()
                    holdTask = null
                    activeEvent?.burstCount = (activeEvent?.burstCount ?: 0) + 1
                    updateActiveEventLocked(score, timestampMillis, baseMetrics)
                }
            }
            is MotionDetectionState.Cooldown -> Unit
            else -> {
                if (positive) {
                    positiveFrames++
                    val metrics = baseMetrics.copy(positiveFrames = positiveFrames)
                    mutableState.value = MotionDetectionState.SuspectedMotion(metrics)
                    if (positiveFrames >= profile.requiredPositiveFrames && confirmationTask == null) {
                        val token = ++candidateToken
                        if (config.triggerDelayMillis == 0L) {
                            confirmMotionLocked(timestampMillis, score)
                        } else {
                            confirmationTask = scheduler.schedule(config.triggerDelayMillis) {
                                synchronized(lock) {
                                    if (running && token == candidateToken &&
                                        mutableState.value is MotionDetectionState.SuspectedMotion
                                    ) {
                                        confirmMotionLocked(clock.nowMillis(), score)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    cancelConfirmationLocked()
                    positiveFrames = 0
                    mutableState.value = MotionDetectionState.NoMotion(
                        baseMetrics.copy(positiveFrames = 0),
                    )
                }
            }
        }
    }

    private fun confirmMotionLocked(timestampMillis: Long, score: Double) {
        cancelConfirmationLocked()
        val event = activeEvent ?: MutableEvent(
            id = idFactory.createId(),
            detectedAtMillis = timestampMillis,
            lastMotionAtMillis = timestampMillis,
            peakScore = score,
            scoreSum = score,
            sampleCount = 1,
            burstCount = 1,
        ).also { activeEvent = it }
        val summary = event.summary(config.sensitivity)
        val metrics = metricsLocked(score).copy(positiveFrames = positiveFrames)
        mutableState.value = MotionDetectionState.MotionConfirmed(summary, metrics)
        mutableEvents.tryEmit(MotionEvent.Confirmed(summary))
    }

    private fun updateActiveEventLocked(score: Double, timestampMillis: Long, metrics: MotionDebugMetrics) {
        val event = activeEvent ?: return
        event.lastMotionAtMillis = timestampMillis
        event.peakScore = maxOf(event.peakScore, score)
        event.scoreSum += score
        event.sampleCount++
        val summary = event.summary(config.sensitivity)
        mutableState.value = MotionDetectionState.MotionConfirmed(summary, metrics)
        mutableEvents.tryEmit(MotionEvent.Activity(summary))
    }

    private fun beginHoldingLocked(timestampMillis: Long, metrics: MotionDebugMetrics) {
        val event = activeEvent ?: return
        val token = currentGeneration
        val until = timestampMillis + config.stopDelayMillis
        mutableState.value = MotionDetectionState.Holding(event.summary(config.sensitivity), until, metrics)
        holdTask?.cancel()
        holdTask = scheduler.schedule(config.stopDelayMillis) {
            synchronized(lock) {
                if (!running || token != currentGeneration ||
                    mutableState.value !is MotionDetectionState.Holding
                ) return@synchronized
                endMotionLocked(clock.nowMillis())
            }
        }
    }

    private fun endMotionLocked(endedAtMillis: Long) {
        holdTask = null
        val event = activeEvent ?: return
        val ended = event.summary(config.sensitivity, endedAtMillis)
        mutableEvents.tryEmit(MotionEvent.Ended(ended))
        val metrics = metricsLocked(0.0).copy(positiveFrames = 0)
        val cooldownUntil = endedAtMillis + config.cooldownMillis
        mutableState.value = MotionDetectionState.Cooldown(ended, cooldownUntil, metrics)
        activeEvent = null
        positiveFrames = 0
        val token = currentGeneration
        cooldownTask?.cancel()
        cooldownTask = scheduler.schedule(config.cooldownMillis) {
            synchronized(lock) {
                if (running && token == currentGeneration &&
                    mutableState.value is MotionDetectionState.Cooldown
                ) {
                    cooldownTask = null
                    mutableState.value = MotionDetectionState.NoMotion(metricsLocked(0.0))
                }
            }
        }
    }

    private fun metricsLocked(score: Double): MotionDebugMetrics {
        val profile = config.resolvedSensitivity().effectiveProfile
        val elapsed = if (firstProcessedAtMillis == 0L) 0L else {
            (lastProcessedTimestamp - firstProcessedAtMillis).coerceAtLeast(1L)
        }
        return MotionDebugMetrics(
            score = score,
            threshold = profile.threshold,
            positiveFrames = positiveFrames,
            requiredPositiveFrames = profile.requiredPositiveFrames,
            analyzerFps = if (elapsed == 0L) 0.0 else processedFrames * 1_000.0 / elapsed,
            averageProcessingMillis = if (processedFrames == 0L) 0.0 else {
                processingTotalNanos / processedFrames / 1_000_000.0
            },
            droppedFrames = droppedFrames,
        )
    }

    private fun refreshMetricsLocked() {
        val current = mutableState.value
        val previousMetrics = when (current) {
            is MotionDetectionState.Initializing -> current.metrics
            is MotionDetectionState.NoMotion -> current.metrics
            is MotionDetectionState.SuspectedMotion -> current.metrics
            is MotionDetectionState.MotionConfirmed -> current.metrics
            is MotionDetectionState.Holding -> current.metrics
            is MotionDetectionState.Cooldown -> current.metrics
            else -> return
        }
        val metrics = metricsLocked(previousMetrics.score).copy(positiveFrames = previousMetrics.positiveFrames)
        mutableState.value = when (current) {
            is MotionDetectionState.Initializing -> current.copy(metrics = metrics)
            is MotionDetectionState.NoMotion -> current.copy(metrics = metrics)
            is MotionDetectionState.SuspectedMotion -> current.copy(metrics = metrics)
            is MotionDetectionState.MotionConfirmed -> current.copy(metrics = metrics)
            is MotionDetectionState.Holding -> current.copy(metrics = metrics)
            is MotionDetectionState.Cooldown -> current.copy(metrics = metrics)
            else -> current
        }
    }

    private fun cancelConfirmationLocked() {
        confirmationTask?.cancel()
        confirmationTask = null
        candidateToken++
    }

    private fun cancelTasksLocked() {
        confirmationTask?.cancel()
        holdTask?.cancel()
        cooldownTask?.cancel()
        confirmationTask = null
        holdTask = null
        cooldownTask = null
        candidateToken++
    }

    private fun failLocked(failure: MotionFailure) {
        cancelTasksLocked()
        analyzer.reset()
        emitEndedIfActiveLocked(clock.nowMillis())
        activeEvent = null
        running = false
        currentGeneration++
        mutableState.value = MotionDetectionState.Error(failure)
    }

    private fun emitEndedIfActiveLocked(endedAtMillis: Long) {
        activeEvent?.let { event ->
            mutableEvents.tryEmit(MotionEvent.Ended(event.summary(config.sensitivity, endedAtMillis)))
        }
    }

    private data class MutableEvent(
        val id: String,
        val detectedAtMillis: Long,
        var lastMotionAtMillis: Long,
        var peakScore: Double,
        var scoreSum: Double,
        var sampleCount: Int,
        var burstCount: Int,
    ) {
        fun summary(sensitivity: com.ashraffarag.sentricam.motion.domain.MotionSensitivity, endedAt: Long? = null) =
            MotionEventSummary(
                eventId = id,
                detectedAtMillis = detectedAtMillis,
                lastMotionAtMillis = lastMotionAtMillis,
                endedAtMillis = endedAt,
                sensitivity = sensitivity,
                peakScore = peakScore.coerceIn(0.0, 1.0),
                averageScore = if (sampleCount == 0) 0.0 else (scoreSum / sampleCount).coerceIn(0.0, 1.0),
                sampleCount = sampleCount,
                burstCount = burstCount,
            )
    }

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 32
    }
}
