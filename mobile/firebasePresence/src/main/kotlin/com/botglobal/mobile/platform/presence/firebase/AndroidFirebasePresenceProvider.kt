package com.botglobal.mobile.platform.presence.firebase

import android.content.Context
import com.botglobal.mobile.platform.presence.PresenceAccount
import com.botglobal.mobile.platform.presence.PresenceGateway
import com.botglobal.mobile.platform.presence.PresenceGatewayEvent
import com.botglobal.mobile.platform.presence.PresenceLease
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin

class AndroidFirebasePresenceProvider(
    private val configuration: FirebasePresenceConfiguration,
    private val backend: FirebasePresenceLeaseBackend,
    private val driverFactory: FirebasePresenceDriverFactory,
    private val scope: CoroutineScope,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : PresenceGateway {
    private val operation = Mutex()
    private val mutableEvents = MutableSharedFlow<PresenceGatewayEvent>(extraBufferCapacity = 16)
    private var active: Active? = null
    override val enabled: Boolean = configuration.enabled
    override val events = mutableEvents.asSharedFlow()

    init { configuration.requireValid() }

    override suspend fun activate(account: PresenceAccount, generation: Long): PresenceLease {
        check(enabled) { "Presence is disabled." }
        require(account.applicationKey == configuration.applicationKey) { "Presence account does not match the configured application." }
        val prior = operation.withLock { active.also { active = null } }
        prior?.stop(invalidate = true)
        val lease = backend.bootstrap(account)
        val connection = try {
            driverFactory.connect(lease, generation) { event -> publish(generation, event) }
        } catch (cancelled: CancellationException) {
            withTimeoutOrNull(2_000) { backend.invalidate(account, lease.leaseId) }
            throw cancelled
        } catch (error: Exception) {
            withTimeoutOrNull(2_000) { backend.invalidate(account, lease.leaseId) }
            throw error
        }
        operation.withLock {
            active = Active(account, lease, connection, generation)
            startMaintenance(active!!)
        }
        return lease
    }

    override suspend fun suspend(lease: PresenceLease, generation: Long) {
        val current = operation.withLock {
            active?.takeIf { it.generation == generation }
                ?.also { it.maintenance?.cancel(); it.maintenance = null; it.suspended = true }
        } ?: return
        // Backgrounding is not evidence that the phone is unreachable. Remove the
        // server capability so readers obtain Unknown, and establish a fresh lease
        // only after the app resumes.
        current.connection.close(generation)
        withTimeoutOrNull(2_000) { backend.invalidate(current.account, current.lease.leaseId) }
        mutableEvents.tryEmit(PresenceGatewayEvent.Unknown(generation, "background_ambiguous"))
    }

    override suspend fun resume(lease: PresenceLease, generation: Long): PresenceLease {
        val current = operation.withLock {
            active?.takeIf { it.generation == generation }
                ?.takeIf { it.suspended }
        } ?: return lease
        val replacement = backend.bootstrap(current.account)
        val nextConnection = try {
            driverFactory.connect(replacement, generation) { event -> publish(generation, event) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            withTimeoutOrNull(2_000) { backend.invalidate(current.account, replacement.leaseId) }
            throw error
        }
        val adopted = operation.withLock {
            if (active !== current || !current.suspended) false
            else {
                current.lease = replacement
                current.connection = nextConnection
                current.suspended = false
                current.needsBootstrap = false
                startMaintenance(current)
                true
            }
        }
        if (!adopted) {
            withTimeoutOrNull(2_000) { nextConnection.close(generation) }
            withTimeoutOrNull(2_000) { backend.invalidate(current.account, replacement.leaseId) }
            return lease
        }
        return replacement
    }

    override suspend fun deactivate(lease: PresenceLease, generation: Long, invalidate: Boolean) {
        val current = operation.withLock {
            active?.takeIf { it.generation == generation }
                ?.also { active = null }
        } ?: return
        current.stop(invalidate)
    }

    private fun startMaintenance(current: Active) {
        current.maintenance = scope.launch {
            while (true) {
                delay(current.lease.heartbeatSeconds.coerceAtLeast(5) * 1_000L)
                if (current.suspended) continue
                if (current.lease.expiresAtEpochMillis - nowEpochMillis() <=
                    current.lease.heartbeatSeconds * 2_000L) {
                    renew(current)
                } else {
                    runCatching { current.connection.heartbeat(current.generation) }
                        .onFailure { if (it is CancellationException) throw it else publish(current.generation, FirebasePresenceDriverEvent.Unknown("heartbeat_failed")) }
                }
            }
        }
    }

    private suspend fun renew(current: Active) {
        val stillCurrent = operation.withLock { active === current && !current.suspended }
        if (!stillCurrent) return
        val replacement = try {
            if (current.needsBootstrap) backend.bootstrap(current.account)
            else backend.renew(current.account, current.lease.leaseId)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            publish(current.generation, FirebasePresenceDriverEvent.Unknown("renewal_failed"))
            return
        }
        current.connection.close(current.generation)
        val nextConnection = try {
            driverFactory.connect(replacement, current.generation) { event -> publish(current.generation, event) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            withTimeoutOrNull(2_000) { backend.invalidate(current.account, replacement.leaseId) }
            operation.withLock {
                if (active === current && !current.suspended) current.needsBootstrap = true
            }
            publish(current.generation, FirebasePresenceDriverEvent.Unknown("reconnect_failed"))
            return
        }
        val adopted = operation.withLock {
            if (active !== current || current.suspended) false
            else {
                current.lease = replacement
                current.connection = nextConnection
                current.needsBootstrap = false
                true
            }
        }
        if (!adopted) {
            withTimeoutOrNull(2_000) { nextConnection.close(current.generation) }
            withTimeoutOrNull(2_000) { backend.invalidate(current.account, replacement.leaseId) }
        }
    }

    private fun publish(generation: Long, event: FirebasePresenceDriverEvent) {
        val mapped = when (event) {
            is FirebasePresenceDriverEvent.Connected -> PresenceGatewayEvent.Connected(generation, event.observedAtEpochMillis)
            is FirebasePresenceDriverEvent.Unknown -> PresenceGatewayEvent.Unknown(generation, event.reason)
            is FirebasePresenceDriverEvent.Failed -> PresenceGatewayEvent.Failed(generation, event.reason)
        }
        mutableEvents.tryEmit(mapped)
    }

    private inner class Active(
        val account: PresenceAccount,
        var lease: PresenceLease,
        var connection: FirebasePresenceConnection,
        val generation: Long,
        var maintenance: Job? = null,
        var suspended: Boolean = false,
        var needsBootstrap: Boolean = false,
    ) {
        suspend fun stop(invalidate: Boolean) {
            maintenance?.cancelAndJoin()
            maintenance = null
            withTimeoutOrNull(2_000) { runCatching { connection.close(generation) } }
            if (invalidate) withTimeoutOrNull(2_000) { backend.invalidate(account, lease.leaseId) }
        }
    }
}

class FirebaseSdkPresenceDriverFactory(
    context: Context,
    private val configuration: FirebasePresenceConfiguration,
    private val scope: CoroutineScope,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : FirebasePresenceDriverFactory {
    private val app: FirebaseApp
    private val auth: FirebaseAuth
    private val database: FirebaseDatabase
    private val authOwner = AtomicLong(0)

    init {
        configuration.requireValid()
        check(configuration.enabled)
        val existing = FirebaseApp.getApps(context).singleOrNull { it.name == configuration.appName }
        if (existing != null) {
            require(existing.options.projectId == configuration.projectId &&
                existing.options.databaseUrl == configuration.databaseUrl &&
                existing.options.applicationId == configuration.applicationId &&
                existing.options.apiKey == configuration.apiKey) {
                "The named presence Firebase app is owned by a different configuration."
            }
        }
        app = existing ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId(configuration.applicationId)
                    .setApiKey(configuration.apiKey)
                    .setProjectId(configuration.projectId)
                    .setDatabaseUrl(configuration.databaseUrl)
                    .build(),
                configuration.appName,
            )
        auth = FirebaseAuth.getInstance(app)
        database = FirebaseDatabase.getInstance(app, configuration.databaseUrl)
        database.setPersistenceEnabled(false)
    }

    override suspend fun connect(
        lease: PresenceLease,
        generation: Long,
        event: (FirebasePresenceDriverEvent) -> Unit,
    ): FirebasePresenceConnection {
        require(lease.databaseUrl == configuration.databaseUrl) { "Lease database origin does not match the approved profile." }
        require(lease.connectionPath.matches(Regex("presenceConnections/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+"))) {
            "Lease connection path is invalid."
        }
        val owner = authOwner.incrementAndGet()
        try {
            withTimeout(5_000) { auth.signInWithCustomToken(lease.customToken).await() }
        } catch (error: Throwable) {
            if (authOwner.compareAndSet(owner, owner + 1)) auth.signOut()
            throw error
        }
        check(authOwner.get() == owner) { "Presence authentication ownership changed during connection." }
        val connection = FirebaseSdkPresenceConnection(
            database,
            lease,
            generation,
            event,
            scope,
            nowEpochMillis,
        ) {
            if (authOwner.compareAndSet(owner, owner + 1)) auth.signOut()
        }
        return try {
            connection.resume(generation)
            connection
        } catch (cancelled: CancellationException) {
            connection.close(generation)
            throw cancelled
        } catch (error: Exception) {
            connection.close(generation)
            throw error
        }
    }
}

private class FirebaseSdkPresenceConnection(
    private val database: FirebaseDatabase,
    private val lease: PresenceLease,
    private val generation: Long,
    private val event: (FirebasePresenceDriverEvent) -> Unit,
    private val scope: CoroutineScope,
    private val nowEpochMillis: () -> Long,
    private val releaseAuth: () -> Unit,
) : FirebasePresenceConnection {
    private val closed = AtomicBoolean(false)
    private val reference = database.getReference(lease.connectionPath)
    private val connected = database.getReference(".info/connected")
    private var listener: ValueEventListener? = null
    private val connectionEpoch = AtomicLong(0)
    private val acknowledgedEpoch = AtomicLong(-1)
    private var registrationJob: Job? = null

    override suspend fun heartbeat(generation: Long) {
        val epoch = connectionEpoch.get()
        if (!current(generation) || acknowledgedEpoch.get() != epoch || expired()) return
        withTimeout(5_000) { reference.setValue(record("connected")).await() }
        if (!current(generation) || acknowledgedEpoch.get() != epoch || expired()) return
    }

    override suspend fun resume(generation: Long) {
        if (!current(generation)) return
        registrationJob?.cancelAndJoin()
        database.purgeOutstandingWrites()
        acknowledgedEpoch.set(-1)
        database.goOnline()
        listener?.let(connected::removeEventListener)
        listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!current(generation)) return
                if (snapshot.getValue(Boolean::class.java) != true) {
                    connectionEpoch.incrementAndGet()
                    acknowledgedEpoch.set(-1)
                    registrationJob?.cancel()
                    database.purgeOutstandingWrites()
                    return
                }
                val epoch = connectionEpoch.incrementAndGet()
                acknowledgedEpoch.set(-1)
                registrationJob?.cancel()
                registrationJob = scope.launch {
                    try {
                        if (expired()) {
                            event(FirebasePresenceDriverEvent.Unknown("lease_expired"))
                            return@launch
                        }
                        val disconnect = reference.onDisconnect()
                        withTimeout(5_000) { disconnect.setValue(record("disconnected")).await() }
                        if (!current(generation, epoch) || expired()) return@launch
                        withTimeout(5_000) { reference.setValue(record("connected")).await() }
                        if (!current(generation, epoch) || expired()) return@launch
                        acknowledgedEpoch.set(epoch)
                        event(FirebasePresenceDriverEvent.Connected(nowEpochMillis()))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (current(generation)) event(FirebasePresenceDriverEvent.Unknown("connection_write_failed"))
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                if (current(generation)) event(FirebasePresenceDriverEvent.Unknown("connection_listener_cancelled"))
            }
        }.also(connected::addValueEventListener)
    }

    override suspend fun suspend(generation: Long) {
        if (!current(generation)) return
        connectionEpoch.incrementAndGet()
        acknowledgedEpoch.set(-1)
        registrationJob?.cancelAndJoin()
        registrationJob = null
        listener?.let(connected::removeEventListener)
        listener = null
        database.purgeOutstandingWrites()
        database.goOffline()
    }

    override suspend fun close(generation: Long) {
        if (generation != this.generation || !closed.compareAndSet(false, true)) return
        connectionEpoch.incrementAndGet()
        acknowledgedEpoch.set(-1)
        withTimeoutOrNull(2_000) { registrationJob?.cancelAndJoin() }
        registrationJob = null
        listener?.let(connected::removeEventListener)
        listener = null
        database.purgeOutstandingWrites()
        database.goOffline()
        releaseAuth()
    }

    private fun current(candidate: Long) = candidate == generation && !closed.get()
    private fun current(candidate: Long, epoch: Long) = current(candidate) && connectionEpoch.get() == epoch
    private fun expired() = nowEpochMillis() >= lease.expiresAtEpochMillis
    private fun record(state: String): Map<String, Any> = mapOf(
        "state" to state,
        "observedAt" to ServerValue.TIMESTAMP,
    )
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        val error = task.exception
        if (error != null) continuation.resumeWithException(error)
        else continuation.resume(task.result)
    }
}
