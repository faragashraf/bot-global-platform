package com.ashraffarag.sentricam.monitoring.android

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.ashraffarag.sentricam.device.android.DeviceConnectivityService
import com.ashraffarag.sentricam.device.domain.DeviceClock
import com.ashraffarag.sentricam.monitoring.domain.MonitoringConfiguration
import com.ashraffarag.sentricam.monitoring.domain.MonitoringLifecycle
import com.ashraffarag.sentricam.monitoring.domain.MonitoringPolicy
import com.ashraffarag.sentricam.monitoring.domain.MonitoringRuntimeReporter
import com.ashraffarag.sentricam.monitoring.domain.MonitoringServiceController
import com.ashraffarag.sentricam.monitoring.domain.MonitoringSession
import com.ashraffarag.sentricam.monitoring.domain.MonitoringSettingsRepository
import com.ashraffarag.sentricam.monitoring.domain.MonitoringState
import com.ashraffarag.sentricam.monitoring.domain.MonitoringStatus
import com.ashraffarag.sentricam.monitoring.domain.MonitoringTransitionResult
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class AndroidMonitoringServiceController(
    context: Context,
    private val clock: DeviceClock,
    private val settings: MonitoringSettingsRepository,
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
) : MonitoringServiceController, MonitoringRuntimeReporter {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(MonitoringState.stopped())
    private val mutex = Mutex()
    private var lastConfiguration = MonitoringConfiguration()
    private var lastPolicy = MonitoringPolicy()

    override val state: StateFlow<MonitoringState> = mutableState.asStateFlow()
    override val lifecycle = MonitoringLifecycle.FOREGROUND_SERVICE

    override suspend fun start(
        configuration: MonitoringConfiguration,
        policy: MonitoringPolicy,
    ): MonitoringTransitionResult {
        val dispatched = mutex.withLock {
            when (mutableState.value.status) {
                MonitoringStatus.STARTING,
                MonitoringStatus.RUNNING,
                MonitoringStatus.RESTARTING,
                -> MonitoringTransitionResult.AlreadyApplied
                MonitoringStatus.STOPPING -> MonitoringTransitionResult.Rejected("monitoring_stopping")
                MonitoringStatus.STOPPED,
                MonitoringStatus.ERROR,
                -> {
                    lastConfiguration = configuration
                    lastPolicy = policy
                    val session = MonitoringSession(
                        sessionIdFactory(),
                        clock.nowMillis(),
                        configuration,
                        policy,
                    )
                    mutableState.value = MonitoringState(MonitoringStatus.STARTING, session)
                    settings.save(settings.load().copy(enabled = true))
                    runCatching {
                        ContextCompat.startForegroundService(
                            appContext,
                            DeviceConnectivityService.intent(
                                appContext,
                                DeviceConnectivityService.ACTION_START_MONITORING,
                            ),
                        )
                    }.fold(
                        onSuccess = { MonitoringTransitionResult.Accepted },
                        onFailure = {
                            settings.save(settings.load().copy(enabled = false))
                            mutableState.value = MonitoringState(
                                MonitoringStatus.ERROR,
                                session,
                                "service_start_failed",
                            )
                            MonitoringTransitionResult.Rejected("service_start_failed")
                        },
                    )
                }
            }
        }
        return if (dispatched == MonitoringTransitionResult.Accepted) {
            awaitStarted()
        } else {
            dispatched
        }
    }

    override suspend fun stop(): MonitoringTransitionResult {
        val dispatched = mutex.withLock {
            val previous = mutableState.value
            settings.save(settings.load().copy(enabled = false))
            when (previous.status) {
                MonitoringStatus.STOPPED -> MonitoringTransitionResult.AlreadyApplied
                MonitoringStatus.STOPPING -> MonitoringTransitionResult.AlreadyApplied
                else -> {
                    mutableState.value = previous.copy(status = MonitoringStatus.STOPPING)
                    runCatching {
                        appContext.startService(
                            DeviceConnectivityService.intent(
                                appContext,
                                DeviceConnectivityService.ACTION_STOP_MONITORING,
                            ),
                        )
                    }.fold(
                        onSuccess = { MonitoringTransitionResult.Accepted },
                        onFailure = {
                            val remainsActive = previous.status == MonitoringStatus.STARTING ||
                                previous.status == MonitoringStatus.RUNNING ||
                                previous.status == MonitoringStatus.RESTARTING
                            settings.save(settings.load().copy(enabled = remainsActive))
                            mutableState.value = previous
                            MonitoringTransitionResult.Rejected("service_stop_failed")
                        },
                    )
                }
            }
        }
        return if (dispatched == MonitoringTransitionResult.Accepted) {
            awaitStopped()
        } else {
            dispatched
        }
    }

    override suspend fun restart(): MonitoringTransitionResult {
        val dispatched = mutex.withLock {
            val previous = mutableState.value
            if (previous.status == MonitoringStatus.STOPPING) {
                return@withLock MonitoringTransitionResult.Rejected("monitoring_stopping")
            }
            settings.save(settings.load().copy(enabled = true))
            mutableState.value = previous.copy(status = MonitoringStatus.RESTARTING)
            runCatching {
                ContextCompat.startForegroundService(
                    appContext,
                    DeviceConnectivityService.intent(
                        appContext,
                        DeviceConnectivityService.ACTION_RESTART_MONITORING,
                    ),
                )
            }.fold(
                onSuccess = { MonitoringTransitionResult.Accepted },
                onFailure = {
                    val wasActive = previous.status == MonitoringStatus.STARTING ||
                        previous.status == MonitoringStatus.RUNNING ||
                        previous.status == MonitoringStatus.RESTARTING
                    settings.save(settings.load().copy(enabled = wasActive))
                    mutableState.value = if (wasActive) {
                        previous
                    } else {
                        previous.copy(status = MonitoringStatus.ERROR, failureCode = "service_restart_failed")
                    }
                    MonitoringTransitionResult.Rejected("service_restart_failed")
                },
            )
        }
        return if (dispatched == MonitoringTransitionResult.Accepted) {
            awaitStarted()
        } else {
            dispatched
        }
    }

    override suspend fun running(): MonitoringTransitionResult = mutex.withLock {
        val current = mutableState.value
        val session = current.session ?: MonitoringSession(
            sessionIdFactory(),
            clock.nowMillis(),
            lastConfiguration,
            lastPolicy,
        )
        mutableState.value = MonitoringState(MonitoringStatus.RUNNING, session)
        MonitoringTransitionResult.Accepted
    }

    override suspend fun stopped(): MonitoringTransitionResult = mutex.withLock {
        mutableState.value = MonitoringState.stopped()
        MonitoringTransitionResult.Accepted
    }

    override suspend fun restarting(): MonitoringTransitionResult = mutex.withLock {
        mutableState.value = mutableState.value.copy(status = MonitoringStatus.RESTARTING, failureCode = null)
        MonitoringTransitionResult.Accepted
    }

    override suspend fun failed(code: String): MonitoringTransitionResult = mutex.withLock {
        mutableState.value = mutableState.value.copy(status = MonitoringStatus.ERROR, failureCode = code)
        MonitoringTransitionResult.Accepted
    }

    private suspend fun awaitStarted(): MonitoringTransitionResult {
        val completed = withTimeoutOrNull(TRANSITION_TIMEOUT_MILLIS) {
            state.first { it.status == MonitoringStatus.RUNNING || it.status == MonitoringStatus.ERROR }
        } ?: return MonitoringTransitionResult.Rejected("monitoring_start_timeout")
        return if (completed.status == MonitoringStatus.RUNNING) {
            MonitoringTransitionResult.Accepted
        } else {
            MonitoringTransitionResult.Rejected(completed.failureCode ?: "monitoring_start_failed")
        }
    }

    private suspend fun awaitStopped(): MonitoringTransitionResult {
        val completed = withTimeoutOrNull(TRANSITION_TIMEOUT_MILLIS) {
            state.first { it.status == MonitoringStatus.STOPPED || it.status == MonitoringStatus.ERROR }
        } ?: return MonitoringTransitionResult.Rejected("monitoring_stop_timeout")
        return if (completed.status == MonitoringStatus.STOPPED) {
            MonitoringTransitionResult.Accepted
        } else {
            MonitoringTransitionResult.Rejected(completed.failureCode ?: "monitoring_stop_failed")
        }
    }

    private companion object {
        const val TRANSITION_TIMEOUT_MILLIS = 20_000L
    }
}
