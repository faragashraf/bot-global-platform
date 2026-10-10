package com.botglobal.mobile.platform.presence

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PresenceController(
    private val scope: CoroutineScope,
    private val gateway: PresenceGateway = DisabledPresenceGateway,
) {
    private val operation = Mutex()
    private val mutableState = MutableStateFlow(
        PresenceSnapshot(state = if (gateway.enabled) PresenceLifecycleState.Idle else PresenceLifecycleState.Disabled),
    )
    private var account: PresenceAccount? = null
    private var lease: PresenceLease? = null
    private var generation = 0L
    private var suspended = false
    val state: StateFlow<PresenceSnapshot> = mutableState.asStateFlow()

    init {
        scope.launch {
            gateway.events.collect { event ->
                operation.withLock {
                    if (event.generation != generation) return@withLock
                    if (suspended) return@withLock
                    mutableState.value = when (event) {
                        is PresenceGatewayEvent.Connected -> mutableState.value.copy(
                            state = PresenceLifecycleState.Connected,
                            lastObservedAtEpochMillis = event.observedAtEpochMillis,
                            safeError = null,
                        )
                        is PresenceGatewayEvent.Unknown -> mutableState.value.copy(
                            state = PresenceLifecycleState.Unknown,
                            safeError = event.reason,
                        )
                        is PresenceGatewayEvent.Failed -> mutableState.value.copy(
                            state = PresenceLifecycleState.Failed,
                            safeError = event.reason,
                        )
                    }
                }
            }
        }
    }

    suspend fun bind(next: PresenceAccount) {
        val plan = operation.withLock {
            if (!gateway.enabled) {
                mutableState.value = PresenceSnapshot(state = PresenceLifecycleState.Disabled)
                return
            }
            if (account?.stableKey == next.stableKey && lease != null) return
            val priorLease = lease
            val priorGeneration = generation
            generation++
            account = next
            lease = null
            suspended = false
            mutableState.value = PresenceSnapshot(next.stableKey, PresenceLifecycleState.Connecting, generation)
            BindPlan(next, generation, priorLease, priorGeneration)
        }
        plan.priorLease?.let { runCatching { gateway.deactivate(it, plan.priorGeneration, invalidate = true) } }
        try {
            val created = gateway.activate(plan.account, plan.generation)
            val adopted = operation.withLock {
                if (plan.generation != generation || account?.stableKey != plan.account.stableKey || suspended) false
                else {
                    lease = created
                    true
                }
            }
            if (!adopted) runCatching { gateway.deactivate(created, plan.generation, invalidate = true) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            operation.withLock {
                if (plan.generation == generation && account?.stableKey == plan.account.stableKey) {
                    mutableState.value = mutableState.value.copy(
                        state = PresenceLifecycleState.Unknown,
                        safeError = "presence_unavailable",
                    )
                }
            }
        }
    }

    suspend fun suspend() {
        val plan = operation.withLock {
            val current = lease ?: return
            suspended = true
            mutableState.value = mutableState.value.copy(state = PresenceLifecycleState.Suspended, safeError = null)
            LeasePlan(current, generation)
        }
        runCatching { gateway.suspend(plan.lease, plan.generation) }
    }

    suspend fun resume() {
        val plan = operation.withLock {
            val current = lease ?: return
            if (!suspended) return
            suspended = false
            mutableState.value = mutableState.value.copy(state = PresenceLifecycleState.Connecting, safeError = null)
            LeasePlan(current, generation)
        }
        try {
            val replacement = gateway.resume(plan.lease, plan.generation)
            val adopted = operation.withLock {
                if (generation != plan.generation || suspended || lease?.leaseId != plan.lease.leaseId) false
                else {
                    lease = replacement
                    true
                }
            }
            if (!adopted) runCatching { gateway.deactivate(replacement, plan.generation, invalidate = true) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            operation.withLock {
                if (generation == plan.generation && !suspended) mutableState.value = mutableState.value.copy(
                    state = PresenceLifecycleState.Unknown,
                    safeError = "presence_unavailable",
                )
            }
        }
    }

    suspend fun clear() {
        val prior = operation.withLock {
            val priorLease = lease
            val priorGeneration = generation
            generation++
            account = null
            lease = null
            suspended = false
            mutableState.value = PresenceSnapshot(
                state = if (gateway.enabled) PresenceLifecycleState.Idle else PresenceLifecycleState.Disabled,
                generation = generation,
            )
            priorLease?.let { LeasePlan(it, priorGeneration) }
        }
        prior?.let { runCatching { gateway.deactivate(it.lease, it.generation, invalidate = true) } }
    }

    private data class BindPlan(
        val account: PresenceAccount,
        val generation: Long,
        val priorLease: PresenceLease?,
        val priorGeneration: Long,
    )

    private data class LeasePlan(val lease: PresenceLease, val generation: Long)
}
