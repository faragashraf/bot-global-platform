package com.ashraffarag.sentricam.device.registration

import com.ashraffarag.sentricam.device.domain.DeviceCapabilities
import com.ashraffarag.sentricam.device.domain.DeviceIdentity
import com.ashraffarag.sentricam.device.domain.CameraOperatingMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

class DeviceRegistrationCoordinator(
    private val repository: DeviceRegistrationRepository,
    private val identity: () -> DeviceIdentity,
    private val capabilities: () -> DeviceCapabilities,
    private val clock: RegistrationClock,
    private val scope: CoroutineScope,
) {
    private val operationGate = Mutex()
    private val stateLock = Any()
    private var generation = 0L
    private var activeJob: Job? = null
    private val mutableState = MutableStateFlow(repository.restoreState())
    val state: StateFlow<RegistrationState> = mutableState.asStateFlow()

    fun configuredBaseUrl(): String = repository.configuredBaseUrl()

    fun operatingMode(): CameraOperatingMode = repository.operatingMode()

    fun configure(rawUrl: String): RegistrationState {
        synchronized(stateLock) {
            generation++
            activeJob?.cancel()
            activeJob = null
        }
        val previousState = state.value
        val previousDetails = previousState.details
        mutableState.value = RegistrationState.Configuring(previousDetails)
        return when (val result = repository.configure(rawUrl)) {
            is RegistrationCallResult.Success -> {
                val restored = if (previousDetails?.serverBaseUrl == result.value) {
                    previousState
                } else {
                    RegistrationState.Unregistered(RegistrationDetails(result.value))
                }
                mutableState.value = restored
                restored
            }
            is RegistrationCallResult.Failure -> failureState(result.failure, previousDetails).also {
                mutableState.value = it
            }
        }
    }

    fun register() {
        synchronized(stateLock) {
            if (activeJob?.isActive == true) return
            activeJob = scope.launch { registerNow() }
        }
    }

    fun retry() = register()

    fun pair(rawPayload: String) {
        synchronized(stateLock) {
            if (activeJob?.isActive == true) return
            activeJob = scope.launch { pairNow(rawPayload) }
        }
    }

    suspend fun pairNow(rawPayload: String): RegistrationState {
        if (!operationGate.tryLock()) return state.value
        val attemptId = nextGeneration()
        val previous = state.value.details
        updateIfCurrent(attemptId, RegistrationState.Configuring(previous))
        try {
            val result = repository.pair(attemptId, rawPayload, identity(), capabilities())
            if (!isCurrent(attemptId)) return state.value
            val next = when (result) {
                is RegistrationCallResult.Success -> when (val saved = repository.saveCredentials(result.value)) {
                    is RegistrationCallResult.Success -> RegistrationState.Registered(result.value.details)
                    is RegistrationCallResult.Failure -> failureState(saved.failure, result.value.details)
                }
                is RegistrationCallResult.Failure -> failureState(result.failure, previous)
            }
            updateIfCurrent(attemptId, next)
            return state.value
        } catch (exception: CancellationException) {
            updateIfCurrent(attemptId, previous?.let(::stateForDetails) ?: RegistrationState.Unregistered())
            throw exception
        } catch (exception: Exception) {
            updateIfCurrent(
                attemptId,
                RegistrationState.Error(RegistrationFailure.UnexpectedFailure(exception), previous),
            )
            return state.value
        } finally {
            operationGate.unlock()
        }
    }

    suspend fun registerNow(): RegistrationState {
        if (!operationGate.tryLock()) return state.value
        val attemptId = nextGeneration()
        val previous = state.value.details
        val attemptDetails = (previous ?: RegistrationDetails(repository.configuredBaseUrl())).copy(
            serverBaseUrl = repository.configuredBaseUrl(),
            installationId = identity().deviceId,
            lastRegistrationAttemptAtMillis = clock.nowMillis(),
        )
        updateIfCurrent(attemptId, RegistrationState.Registering(attemptId, attemptDetails))
        try {
            val result = repository.register(attemptId, identity(), capabilities())
            if (!isCurrent(attemptId)) return state.value
            val next = when (result) {
                is RegistrationCallResult.Success -> when (val saved = repository.saveCredentials(result.value)) {
                    is RegistrationCallResult.Success -> RegistrationState.Registered(result.value.details)
                    is RegistrationCallResult.Failure -> failureState(saved.failure, result.value.details)
                }
                is RegistrationCallResult.Failure -> failureState(result.failure, attemptDetails)
            }
            updateIfCurrent(attemptId, next)
            return state.value
        } catch (exception: CancellationException) {
            if (isCurrent(attemptId)) {
                updateIfCurrent(attemptId, previous?.let(::stateForDetails) ?: RegistrationState.Unregistered())
            }
            throw exception
        } catch (exception: Exception) {
            updateIfCurrent(
                attemptId,
                RegistrationState.Error(RegistrationFailure.UnexpectedFailure(exception), attemptDetails),
            )
            return state.value
        } finally {
            operationGate.unlock()
        }
    }

    fun testConnection() {
        synchronized(stateLock) {
            if (activeJob?.isActive == true) return
            activeJob = scope.launch { testConnectionNow() }
        }
    }

    suspend fun testConnectionNow(): RegistrationState {
        if (!operationGate.tryLock()) return state.value
        val attemptId = nextGeneration()
        val previous = state.value
        mutableState.value = RegistrationState.Configuring(previous.details)
        try {
            when (val result = repository.testConnection(attemptId)) {
                is RegistrationCallResult.Failure -> updateIfCurrent(
                    attemptId,
                    failureState(result.failure, previous.details),
                )
                is RegistrationCallResult.Success -> {
                    val details = (previous.details ?: RegistrationDetails(repository.configuredBaseUrl())).copy(
                        lastSuccessfulConnectionAtMillis = result.value,
                    )
                    val updated = if (previous.details == null) {
                        RegistrationCallResult.Success(details)
                    } else {
                        repository.updateLastConnection(details, result.value)
                    }
                    val next = when (updated) {
                        is RegistrationCallResult.Success -> when (previous) {
                            is RegistrationState.Registered -> RegistrationState.Registered(updated.value)
                            is RegistrationState.TokenExpired -> RegistrationState.TokenExpired(updated.value)
                            else -> if (updated.value.serverDeviceId != null) {
                                stateForDetails(updated.value)
                            } else {
                                RegistrationState.Unregistered(updated.value)
                            }
                        }
                        is RegistrationCallResult.Failure -> failureState(updated.failure, details)
                    }
                    updateIfCurrent(attemptId, next)
                }
            }
            return state.value
        } catch (exception: CancellationException) {
            updateIfCurrent(attemptId, previous)
            throw exception
        } catch (exception: Exception) {
            updateIfCurrent(
                attemptId,
                RegistrationState.Error(RegistrationFailure.UnexpectedFailure(exception), previous.details),
            )
            return state.value
        } finally {
            operationGate.unlock()
        }
    }

    fun forgetRegistration(): RegistrationState {
        synchronized(stateLock) {
            generation++
            activeJob?.cancel()
            activeJob = null
        }
        val next = when (val result = repository.clearCredentials()) {
            is RegistrationCallResult.Success -> RegistrationState.Unregistered(
                RegistrationDetails(repository.configuredBaseUrl()),
            )
            is RegistrationCallResult.Failure -> RegistrationState.Error(result.failure, null)
        }
        mutableState.value = next
        return next
    }

    fun maybeRegisterAutomatically(maxAttempts: Int = 3) {
        val initial = state.value
        if (operatingMode() !is CameraOperatingMode.HubManaged ||
            initial !is RegistrationState.TokenExpired ||
            maxAttempts <= 0 ||
            !repository.canRegisterAutomatically()
        ) return
        synchronized(stateLock) {
            if (activeJob?.isActive == true) return
            activeJob = scope.launch {
                repeat(maxAttempts.coerceAtMost(3)) { index ->
                    val result = registerNow()
                    if (result is RegistrationState.Registered || result !is RegistrationState.ConnectionFailed) {
                        return@launch
                    }
                    if (index < maxAttempts - 1) delay(1_000L shl index)
                }
            }
        }
    }

    fun refreshExpiredCredentials() {
        val registered = state.value as? RegistrationState.Registered ?: return
        if ((registered.details.accessTokenExpiresAtMillis ?: 0L) > clock.nowMillis()) return
        synchronized(stateLock) {
            if (mutableState.value is RegistrationState.Registered) {
                mutableState.value = RegistrationState.TokenExpired(registered.details)
            }
        }
        maybeRegisterAutomatically()
    }

    /**
     * Renews credentials rejected by the configured Hub even when their embedded expiration has
     * not elapsed. This covers server signing-key rotation and restored credentials without
     * clearing installation identity or local recordings.
     */
    fun refreshRejectedCredentials() {
        val registered = state.value as? RegistrationState.Registered ?: return
        synchronized(stateLock) {
            if (mutableState.value is RegistrationState.Registered) {
                mutableState.value = RegistrationState.TokenExpired(registered.details)
            }
        }
        maybeRegisterAutomatically()
    }

    private fun nextGeneration(): Long = synchronized(stateLock) { ++generation }

    private fun isCurrent(attemptId: Long): Boolean = synchronized(stateLock) { generation == attemptId }

    private fun updateIfCurrent(attemptId: Long, next: RegistrationState) {
        if (isCurrent(attemptId)) mutableState.value = next
    }

    private fun stateForDetails(details: RegistrationDetails): RegistrationState =
        if ((details.accessTokenExpiresAtMillis ?: 0L) <= clock.nowMillis()) {
            RegistrationState.TokenExpired(details)
        } else {
            RegistrationState.Registered(details)
        }

    private fun failureState(
        failure: RegistrationFailure,
        details: RegistrationDetails?,
    ): RegistrationState = when (failure) {
        is RegistrationFailure.InvalidServerUrl,
        is RegistrationFailure.CleartextBlocked,
        -> RegistrationState.InvalidConfiguration(failure, details)
        is RegistrationFailure.NetworkUnavailable,
        is RegistrationFailure.Timeout,
        is RegistrationFailure.ServerUnreachable,
        -> RegistrationState.ConnectionFailed(failure, details)
        is RegistrationFailure.ValidationRejected,
        is RegistrationFailure.Unauthorized,
        is RegistrationFailure.Conflict,
        -> RegistrationState.ServerRejected(failure, details)
        is RegistrationFailure.TokenInvalid -> details?.let(RegistrationState::TokenExpired)
            ?: RegistrationState.Error(failure, null)
        is RegistrationFailure.SerializationFailure,
        is RegistrationFailure.SecureStorageFailure,
        is RegistrationFailure.UnexpectedFailure,
        -> RegistrationState.Error(failure, details)
    }
}
