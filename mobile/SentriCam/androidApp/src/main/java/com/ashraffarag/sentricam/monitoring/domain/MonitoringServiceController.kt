package com.ashraffarag.sentricam.monitoring.domain

import com.ashraffarag.sentricam.device.domain.DeviceClock
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface MonitoringServiceController {
    val state: StateFlow<MonitoringState>
    val lifecycle: MonitoringLifecycle

    suspend fun start(
        configuration: MonitoringConfiguration = MonitoringConfiguration(),
        policy: MonitoringPolicy = MonitoringPolicy(),
    ): MonitoringTransitionResult

    suspend fun stop(): MonitoringTransitionResult
    suspend fun restart(): MonitoringTransitionResult
}

/** Internal lifecycle reporting boundary used by the Android service host. */
interface MonitoringRuntimeReporter {
    suspend fun running(): MonitoringTransitionResult
    suspend fun stopped(): MonitoringTransitionResult
    suspend fun restarting(): MonitoringTransitionResult
    suspend fun failed(code: String): MonitoringTransitionResult
}

class DefaultMonitoringServiceController(
    private val clock: DeviceClock,
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
    override val lifecycle: MonitoringLifecycle = MonitoringLifecycle.ACTIVITY_FOREGROUND,
) : MonitoringServiceController {
    private val mutableState = MutableStateFlow(MonitoringState.stopped())
    override val state: StateFlow<MonitoringState> = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var lastConfiguration = MonitoringConfiguration()
    private var lastPolicy = MonitoringPolicy()

    override suspend fun start(
        configuration: MonitoringConfiguration,
        policy: MonitoringPolicy,
    ): MonitoringTransitionResult = mutex.withLock {
        when (mutableState.value.status) {
            MonitoringStatus.RUNNING,
            MonitoringStatus.STARTING,
            -> MonitoringTransitionResult.AlreadyApplied

            MonitoringStatus.STOPPING -> MonitoringTransitionResult.Rejected("monitoring_stopping")
            MonitoringStatus.STOPPED,
            MonitoringStatus.ERROR,
            MonitoringStatus.RESTARTING,
            -> {
                lastConfiguration = configuration
                lastPolicy = policy
                val session = MonitoringSession(
                    sessionId = sessionIdFactory(),
                    startedAtMillis = clock.nowMillis(),
                    configuration = configuration,
                    policy = policy,
                )
                mutableState.value = MonitoringState(MonitoringStatus.STARTING, session)
                // This phase intentionally coordinates lifecycle only. No camera/background service is started.
                mutableState.value = MonitoringState(MonitoringStatus.RUNNING, session)
                MonitoringTransitionResult.Accepted
            }
        }
    }

    override suspend fun stop(): MonitoringTransitionResult = mutex.withLock {
        when (mutableState.value.status) {
            MonitoringStatus.STOPPED -> MonitoringTransitionResult.AlreadyApplied
            MonitoringStatus.STOPPING -> MonitoringTransitionResult.AlreadyApplied
            MonitoringStatus.STARTING,
            MonitoringStatus.RUNNING,
            MonitoringStatus.ERROR,
            MonitoringStatus.RESTARTING,
            -> {
                mutableState.value = mutableState.value.copy(status = MonitoringStatus.STOPPING)
                mutableState.value = MonitoringState.stopped()
                MonitoringTransitionResult.Accepted
            }
        }
    }

    override suspend fun restart(): MonitoringTransitionResult = mutex.withLock {
        val current = mutableState.value
        if (current.status == MonitoringStatus.STOPPING) {
            return@withLock MonitoringTransitionResult.Rejected("monitoring_stopping")
        }
        mutableState.value = MonitoringState.stopped()
        val session = MonitoringSession(
            sessionId = sessionIdFactory(),
            startedAtMillis = clock.nowMillis(),
            configuration = current.session?.configuration ?: lastConfiguration,
            policy = current.session?.policy ?: lastPolicy,
        )
        mutableState.value = MonitoringState(MonitoringStatus.STARTING, session)
        mutableState.value = MonitoringState(MonitoringStatus.RUNNING, session)
        MonitoringTransitionResult.Accepted
    }
}
