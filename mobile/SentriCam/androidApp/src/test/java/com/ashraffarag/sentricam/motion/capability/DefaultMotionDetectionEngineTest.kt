package com.ashraffarag.sentricam.motion.capability

import com.ashraffarag.sentricam.motion.domain.FrameDifferenceMotionAnalyzer
import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig
import com.ashraffarag.sentricam.motion.domain.MotionDetectionState
import com.ashraffarag.sentricam.motion.domain.MotionFailureCode
import com.ashraffarag.sentricam.motion.domain.MotionFrame
import com.ashraffarag.sentricam.motion.domain.MotionSensitivity
import com.ashraffarag.sentricam.motion.domain.MotionStartResult
import com.ashraffarag.sentricam.motion.domain.MotionStopResult
import com.ashraffarag.sentricam.motion.domain.MotionUpdateResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultMotionDetectionEngineTest {
    @Test
    fun consecutiveFramesConfirmMotionButOneOutlierDoesNot() = runBlocking {
        val fixture = Fixture()
        fixture.start()
        fixture.baseline()
        fixture.submit(leftBright())
        assertTrue(fixture.engine.state.value is MotionDetectionState.SuspectedMotion)
        fixture.submit(leftBright())
        assertTrue(fixture.engine.state.value is MotionDetectionState.NoMotion)
        fixture.submit(rightBright())
        fixture.submit(leftBright())
        assertTrue(fixture.engine.state.value is MotionDetectionState.MotionConfirmed)
    }

    @Test
    fun triggerDelayDefersConfirmation() = runBlocking {
        val fixture = Fixture(config = config(triggerDelayMillis = 1_000L))
        fixture.start()
        fixture.baseline()
        fixture.submit(leftBright())
        fixture.submit(rightBright())
        assertTrue(fixture.engine.state.value is MotionDetectionState.SuspectedMotion)
        fixture.scheduler.run(1_000L)
        assertTrue(fixture.engine.state.value is MotionDetectionState.MotionConfirmed)
    }

    @Test
    fun stopDelayEndsMotionAndStartsCooldown() = runBlocking {
        val fixture = Fixture()
        fixture.confirm()
        fixture.submit(rightBright())
        assertTrue(fixture.engine.state.value is MotionDetectionState.Holding)
        fixture.scheduler.run(5_000L)
        assertTrue(fixture.engine.state.value is MotionDetectionState.Cooldown)
    }

    @Test
    fun newMotionDuringHoldingCancelsStop() = runBlocking {
        val fixture = Fixture()
        fixture.confirm()
        fixture.submit(rightBright())
        fixture.submit(leftBright())
        assertTrue(fixture.engine.state.value is MotionDetectionState.MotionConfirmed)
        assertFalse(fixture.scheduler.hasActive(5_000L))
    }

    @Test
    fun cooldownReturnsToNoMotion() = runBlocking {
        val fixture = Fixture()
        fixture.confirm()
        fixture.submit(rightBright())
        fixture.scheduler.run(5_000L)
        fixture.scheduler.run(3_000L)
        assertTrue(fixture.engine.state.value is MotionDetectionState.NoMotion)
    }

    @Test
    fun disablingMonitoringCancelsAllJobsAndFrames() = runBlocking {
        val fixture = Fixture()
        fixture.confirm()
        assertEquals(MotionStopResult.Stopped, fixture.engine.stop())
        assertTrue(fixture.engine.state.value is MotionDetectionState.Disabled)
        assertFalse(fixture.scheduler.hasAnyActive())
        fixture.submit(rightBright())
        assertTrue(fixture.engine.state.value is MotionDetectionState.Disabled)
    }

    @Test
    fun oldGenerationAndLensSwitchWarmupAreIgnored() = runBlocking {
        val fixture = Fixture()
        fixture.start()
        val oldGeneration = fixture.engine.generation
        val newGeneration = fixture.engine.resetForCameraChange()
        assertNotEquals(oldGeneration, newGeneration)
        fixture.engine.submitFrame(MotionFrame(leftBright(), 4, 4, 1_000L, oldGeneration))
        val state = fixture.engine.state.value as MotionDetectionState.Initializing
        assertEquals(fixture.config.warmupFrameCount, state.warmupFramesRemaining)
    }

    @Test
    fun cameraResetDuringConfirmedMotionPreservesEventUntilWarmupCompletes() = runBlocking {
        val fixture = Fixture()
        fixture.confirm()
        val eventId = (fixture.engine.state.value as MotionDetectionState.MotionConfirmed).event.eventId
        fixture.engine.resetForCameraChange()
        fixture.baseline()
        val resumed = fixture.engine.state.value as MotionDetectionState.MotionConfirmed
        assertEquals(eventId, resumed.event.eventId)
    }

    @Test
    fun invalidConfigIsRejected() = runBlocking {
        val fixture = Fixture(config = config(stopDelayMillis = 0L))
        val result = fixture.engine.start(fixture.config) as MotionStartResult.Rejected
        assertEquals(MotionFailureCode.INVALID_CONFIG, result.failure.code)
    }

    @Test
    fun analyzerFailureMovesToSafeErrorAndStopsJobs() = runBlocking {
        val fixture = Fixture()
        fixture.start()
        fixture.engine.reportAnalyzerFailure(IllegalStateException("analysis"))
        assertTrue(fixture.engine.state.value is MotionDetectionState.Error)
        assertFalse(fixture.scheduler.hasAnyActive())
    }

    @Test
    fun unsupportedCombinationHasSpecificFailure() = runBlocking {
        val fixture = Fixture()
        fixture.start()
        fixture.engine.reportAnalyzerFailure(IllegalStateException("combination"), true)
        val error = fixture.engine.state.value as MotionDetectionState.Error
        assertEquals(MotionFailureCode.UNSUPPORTED_USE_CASE_COMBINATION, error.failure.code)
    }

    @Test
    fun updateConfigReentersWarmupAndAppliesSensitivity() = runBlocking {
        val fixture = Fixture()
        fixture.start()
        val updated = fixture.config.copy(sensitivity = MotionSensitivity.LOW)
        assertEquals(MotionUpdateResult.Updated, fixture.engine.updateConfig(updated))
        assertTrue(fixture.engine.state.value is MotionDetectionState.Initializing)
    }

    @Test
    fun inactiveCustomValueDoesNotChangeOrRestartPresetAnalyzerButAdvancedModeDoes() = runBlocking {
        val fixture = Fixture(config = config().copy(sensitivity = MotionSensitivity.MEDIUM, advancedSensitivity = 10))
        fixture.start()
        fixture.baseline()
        val preset = fixture.metrics()

        assertEquals(MotionUpdateResult.Updated, fixture.engine.updateConfig(fixture.config.copy(advancedSensitivity = 90)))
        assertFalse(fixture.engine.state.value is MotionDetectionState.Initializing)
        assertEquals(preset.threshold, fixture.metrics().threshold, 0.0)
        assertEquals(preset.requiredPositiveFrames, fixture.metrics().requiredPositiveFrames)

        assertEquals(
            MotionUpdateResult.Updated,
            fixture.engine.updateConfig(fixture.config.copy(sensitivity = MotionSensitivity.ADVANCED, advancedSensitivity = 90)),
        )
        assertTrue(fixture.engine.state.value is MotionDetectionState.Initializing)
        fixture.baseline()
        val custom = fixture.metrics()
        val expected = com.ashraffarag.sentricam.motion.domain.MotionSensitivityPolicy.profile(MotionSensitivity.ADVANCED, 90)
        assertEquals(expected.threshold, custom.threshold, 0.0)
        assertEquals(expected.requiredPositiveFrames, custom.requiredPositiveFrames)
        assertTrue(custom.threshold < preset.threshold)
    }

    @Test
    fun materiallyDifferentCustomValuesChangeDetectionOutput() = runBlocking {
        val lowCustom = Fixture(config = config().copy(sensitivity = MotionSensitivity.ADVANCED, advancedSensitivity = 0))
        val highCustom = Fixture(config = config().copy(sensitivity = MotionSensitivity.ADVANCED, advancedSensitivity = 100))
        lowCustom.start()
        highCustom.start()
        lowCustom.baseline()
        highCustom.baseline()

        repeat(2) { index ->
            val frame = if (index % 2 == 0) leftBright() else rightBright()
            lowCustom.submit(frame)
            highCustom.submit(frame)
        }

        assertFalse(lowCustom.engine.state.value is MotionDetectionState.MotionConfirmed)
        assertTrue(highCustom.engine.state.value is MotionDetectionState.MotionConfirmed)
        assertEquals(5, lowCustom.metrics().requiredPositiveFrames)
        assertEquals(2, highCustom.metrics().requiredPositiveFrames)
    }

    @Test
    fun frameThrottleDropsFastFrames() = runBlocking {
        val fixture = Fixture(config = config(frameIntervalMillis = 200L))
        fixture.start()
        fixture.submit(ByteArray(16), advance = 0L)
        fixture.submit(leftBright(), advance = 20L)
        val state = fixture.engine.state.value as MotionDetectionState.Initializing
        assertEquals(1L, state.metrics.droppedFrames)
    }

    @Test
    fun simulateMotionCreatesConfirmedEvent() = runBlocking {
        val fixture = Fixture()
        fixture.start()
        fixture.engine.simulateMotion(2_000L)
        val state = fixture.engine.state.value as MotionDetectionState.MotionConfirmed
        assertEquals("motion-1", state.event.eventId)
        assertEquals(1.0, state.event.peakScore, 0.0)
    }

    @Test
    fun repeatedStartAndStopAreIdempotent() = runBlocking {
        val fixture = Fixture()
        assertEquals(MotionStartResult.Started, fixture.engine.start(fixture.config))
        assertEquals(MotionStartResult.AlreadyRunning, fixture.engine.start(fixture.config))
        assertEquals(MotionStopResult.Stopped, fixture.engine.stop())
        assertEquals(MotionStopResult.NotRunning, fixture.engine.stop())
    }

    @Test
    fun sensitivityProfilesRequireDifferentFrameCounts() {
        val low = com.ashraffarag.sentricam.motion.domain.MotionSensitivityPolicy.profile(MotionSensitivity.LOW)
        val high = com.ashraffarag.sentricam.motion.domain.MotionSensitivityPolicy.profile(MotionSensitivity.HIGH)
        assertTrue(low.threshold > high.threshold)
        assertTrue(low.requiredPositiveFrames > high.requiredPositiveFrames)
    }

    @Test
    fun stateFlowTransitionsFollowExpectedOrder() = runBlocking {
        val fixture = Fixture()
        val observed = mutableListOf<String>()
        val job = launch(Dispatchers.Unconfined) {
            fixture.engine.state.collect { observed += it.javaClass.simpleName }
        }
        fixture.start()
        fixture.baseline()
        fixture.submit(leftBright())
        fixture.submit(rightBright())
        job.cancel()
        assertEquals("Disabled", observed.first())
        assertTrue(observed.contains("Initializing"))
        assertTrue(observed.contains("NoMotion"))
        assertTrue(observed.contains("SuspectedMotion"))
        assertEquals("MotionConfirmed", observed.last())
    }

    private class Fixture(val config: MotionDetectionConfig = config()) {
        val scheduler = FakeScheduler()
        var now = 1_000L
        var nextId = 0
        val engine = DefaultMotionDetectionEngine(
            FrameDifferenceMotionAnalyzer(),
            MotionClock { now },
            MotionIdFactory { "motion-${++nextId}" },
            scheduler,
        )

        suspend fun start() {
            assertEquals(MotionStartResult.Started, engine.start(config))
        }

        fun baseline() {
            submit(ByteArray(16))
            submit(ByteArray(16))
        }

        fun confirm() {
            runBlocking { start() }
            baseline()
            submit(leftBright())
            submit(rightBright())
            check(engine.state.value is MotionDetectionState.MotionConfirmed)
        }

        fun submit(bytes: ByteArray, advance: Long = 100L) {
            now += advance
            engine.submitFrame(MotionFrame(bytes, 4, 4, now, engine.generation))
        }

        fun metrics() = when (val current = engine.state.value) {
            is MotionDetectionState.Initializing -> current.metrics
            is MotionDetectionState.NoMotion -> current.metrics
            is MotionDetectionState.SuspectedMotion -> current.metrics
            is MotionDetectionState.MotionConfirmed -> current.metrics
            is MotionDetectionState.Holding -> current.metrics
            is MotionDetectionState.Cooldown -> current.metrics
            else -> error("Motion metrics are unavailable in ${current.javaClass.simpleName}")
        }
    }

    private class FakeScheduler : MotionScheduler {
        data class Task(val delay: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private val tasks = mutableListOf<Task>()
        override fun schedule(delayMillis: Long, task: () -> Unit): MotionScheduledTask {
            val scheduled = Task(delayMillis, task)
            tasks += scheduled
            return MotionScheduledTask { scheduled.cancelled = true }
        }
        fun run(delay: Long) {
            val task = tasks.first { !it.cancelled && it.delay == delay }
            task.cancelled = true
            task.action()
        }
        fun hasActive(delay: Long) = tasks.any { !it.cancelled && it.delay == delay }
        fun hasAnyActive() = tasks.any { !it.cancelled }
    }

    private companion object {
        fun config(
            triggerDelayMillis: Long = 0L,
            stopDelayMillis: Long = 5_000L,
            frameIntervalMillis: Long = 50L,
        ) = MotionDetectionConfig(
            enabled = true,
            sensitivity = MotionSensitivity.HIGH,
            triggerDelayMillis = triggerDelayMillis,
            stopDelayMillis = stopDelayMillis,
            cooldownMillis = 3_000L,
            frameIntervalMillis = frameIntervalMillis,
            warmupFrameCount = 1,
        )

        fun leftBright() = ByteArray(16) { if (it % 4 < 2) 255.toByte() else 0 }
        fun rightBright() = ByteArray(16) { if (it % 4 >= 2) 255.toByte() else 0 }
    }
}
