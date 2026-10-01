package com.ashraffarag.sentricam.communication.signalr

import com.ashraffarag.sentricam.device.domain.DeviceSnapshot
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random
import com.ashraffarag.sentricam.live.capability.LiveSignalReceiver
import com.ashraffarag.sentricam.live.domain.LiveDeviceCapabilities
import com.ashraffarag.sentricam.live.domain.LiveIceCandidate
import com.ashraffarag.sentricam.live.domain.LiveSessionDescription
import com.ashraffarag.sentricam.live.domain.LiveSessionStatistics
import com.ashraffarag.sentricam.live.domain.LiveSessionStatusUpdate
import com.ashraffarag.sentricam.cameracontrol.capability.CameraControlSignalReceiver
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlCommandResult
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlDeviceReport
import com.ashraffarag.sentricam.device.recovery.OperationalHealthSnapshot

data class SignalRConfiguration(
    val hubUrl: String,
    val serverTimeoutMillis: Long = 30_000L,
    val keepAliveIntervalMillis: Long = 15_000L,
)

data class ConnectionPolicy(
    val heartbeatIntervalMillis: Long = 15_000L,
    val transportPulseIntervalMillis: Long = 1_000L,
    val reconnectDelaysMillis: List<Long> = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L),
) {
    init {
        require(heartbeatIntervalMillis > 0L)
        require(transportPulseIntervalMillis > 0L)
        require(reconnectDelaysMillis.isNotEmpty())
        require(reconnectDelaysMillis.all { it >= 0L })
    }

}

fun interface SignalRJitter {
    fun apply(baseDelayMillis: Long, attempt: Int): Long
}

object BoundedRandomSignalRJitter : SignalRJitter {
    override fun apply(baseDelayMillis: Long, attempt: Int): Long {
        if (baseDelayMillis <= 1L) return baseDelayMillis
        val variance = (baseDelayMillis / 5L).coerceAtLeast(1L)
        return (baseDelayMillis + Random.nextLong(-variance, variance + 1L)).coerceAtLeast(0L)
    }
}

sealed interface SignalRState {
    val reconnectAttempts: Int

    data object Stopped : SignalRState {
        override val reconnectAttempts = 0
    }

    data class Disconnected(
        val reasonCode: String? = null,
        val lastConnectedAtMillis: Long? = null,
        override val reconnectAttempts: Int = 0,
    ) : SignalRState

    data class Connecting(
        override val reconnectAttempts: Int = 0,
    ) : SignalRState

    data class Connected(
        val connectionId: String,
        val connectedAtMillis: Long,
        val lastHeartbeatAtMillis: Long? = null,
        override val reconnectAttempts: Int = 0,
    ) : SignalRState

    data class Reconnecting(
        override val reconnectAttempts: Int,
        val nextAttemptAtMillis: Long,
        val reasonCode: String,
    ) : SignalRState

    data class AuthenticationFailed(
        val reasonCode: String,
        override val reconnectAttempts: Int,
    ) : SignalRState

    data class ServerUnavailable(
        val reasonCode: String,
        override val reconnectAttempts: Int,
    ) : SignalRState

    data class Error(
        val reasonCode: String,
        override val reconnectAttempts: Int,
    ) : SignalRState
}

enum class SignalRFailureCode(val stableCode: String, val retryAllowed: Boolean) {
    NETWORK_OFFLINE("network_offline", true),
    AUTHENTICATION_FAILED("authentication_failed", false),
    CREDENTIALS_MISSING("credentials_missing", false),
    TOKEN_EXPIRED("token_expired", false),
    INVALID_CONFIGURATION("invalid_configuration", false),
    SERVER_UNAVAILABLE("server_unavailable", true),
    CONNECTION_CLOSED("connection_closed", true),
    HEARTBEAT_FAILED("heartbeat_failed", true),
    PROTOCOL_ERROR("protocol_error", true),
    CONTRACT_ERROR("contract_error", false),
    SECURE_STORAGE_FAILED("secure_storage_failed", false),
    UNEXPECTED("unexpected", true),
}

class SignalRTransportException(
    val failureCode: SignalRFailureCode,
    cause: Throwable? = null,
    val diagnostics: SignalRProtocolDiagnostics? = null,
) : RuntimeException(failureCode.stableCode, cause)

data class SignalRProtocolDiagnostics(
    val phase: String,
    val connectionState: String,
    val transport: String = "automatic",
    val protocol: String = "json",
    val lastInboundEvent: String? = null,
    val lastOutboundInvocation: String? = null,
)

data class SignalRConnection(
    val connectionId: String,
)

data class DevicePairingRevoked(
    val deviceId: String?,
    val revokedAtUtc: String?,
    val reason: String?,
)

fun interface PairingRevocationReceiver {
    fun revoked(revocation: DevicePairingRevoked)
}

data class SignalRHeartbeat(
    val deviceId: String,
    val connectionId: String,
    val snapshotVersion: Long,
    val timestamp: String,
    val currentDeviceSnapshot: DeviceSnapshot,
)

data class SignalRHeartbeatAcknowledgement(
    val deviceId: String?,
    val connectionId: String?,
    val snapshotVersion: Long?,
    val lastHeartbeatAtUtc: String?,
    val serverUtcNow: String?,
    val serverUtcOffsetMinutes: Int? = null,
    val serverTimeZoneId: String? = null,
)

data class DeviceOperationalHealthReport(
    val deviceId: String,
    val health: OperationalHealthSnapshot,
    val reportedAtUtc: String,
)

data class SignalRConnectionMetrics(
    val connectionId: String? = null,
    val connectedAtMillis: Long? = null,
    val lastHeartbeatAtMillis: Long? = null,
    val reconnectAttempts: Int = 0,
    val commandsInFlight: Int = 0,
)

internal data class SignalRCredential(
    val serverDeviceId: String,
    val accessToken: String,
    val expiresAtMillis: Long,
)

fun interface SignalRAccessTokenProvider {
    fun accessToken(): String
}

fun interface ConnectionLifecycle {
    fun closed(cause: Throwable?)
}

interface SignalRClient : AutoCloseable {
    suspend fun connect(
        configuration: SignalRConfiguration,
        accessTokenProvider: SignalRAccessTokenProvider,
        lifecycle: ConnectionLifecycle,
        commandReceiver: SignalRCommandReceiver,
        pairingRevocationReceiver: PairingRevocationReceiver,
        liveReceiver: LiveSignalReceiver,
        cameraControlReceiver: CameraControlSignalReceiver,
    ): SignalRConnection

    suspend fun heartbeat(heartbeat: SignalRHeartbeat): SignalRHeartbeatAcknowledgement

    suspend fun transportPulse()

    suspend fun completeCommand(result: RemoteDeviceCommandResult)

    suspend fun reportLiveCapabilities(capabilities: LiveDeviceCapabilities)
    suspend fun submitLiveAnswer(answer: LiveSessionDescription)
    suspend fun submitLiveCandidate(candidate: LiveIceCandidate)
    suspend fun reportLiveState(update: LiveSessionStatusUpdate)
    suspend fun reportLiveStatistics(statistics: LiveSessionStatistics)
    suspend fun reportCameraControlState(report: CameraControlDeviceReport)
    suspend fun reportOperationalHealth(report: DeviceOperationalHealthReport) = Unit
    suspend fun completeCameraControlCommand(result: CameraControlCommandResult)

    suspend fun disconnect()
}

data class RemoteDeviceCommand(
    val commandId: String,
    val deviceId: String,
    val commandType: Int,
    val correlationId: String,
    val requestedAtUtc: String,
)

data class RemoteDeviceCommandResult(
    val commandId: String,
    val deviceId: String,
    val outcome: Int,
    val resultCode: String,
    val snapshot: DeviceSnapshot?,
    val completedAtUtc: String,
)

fun interface SignalRCommandReceiver {
    fun received(command: RemoteDeviceCommand)
}

fun interface SignalRCommandExecutor {
    suspend fun execute(command: RemoteDeviceCommand): RemoteDeviceCommandResult
}

fun interface SignalRClientFactory {
    fun create(): SignalRClient
}

data class SignalRNetworkState(
    val available: Boolean,
    val validated: Boolean,
) {
    init {
        require(!validated || available) { "A validated network must also be available" }
    }

    companion object {
        val Unavailable = SignalRNetworkState(available = false, validated = false)
        val Available = SignalRNetworkState(available = true, validated = false)
        val Validated = SignalRNetworkState(available = true, validated = true)
    }
}

interface SignalRNetworkMonitor {
    val state: StateFlow<SignalRNetworkState>
}

fun interface SignalRClock {
    fun nowMillis(): Long
}

fun interface SignalRDelay {
    suspend fun wait(delayMillis: Long)
}

fun interface SnapshotVersionProvider {
    fun next(): Long
}

interface SignalRLogger {
    fun connected(serverHost: String, connectionId: String, reconnectAttempts: Int)
    fun disconnected(connectionId: String?, failure: SignalRTransportException)
    fun reconnecting(serverHost: String, attempt: Int, delayMillis: Long, reasonCode: String)
    fun heartbeat(connectionId: String, snapshotVersion: Long)
    fun pendingBackoffCancelled(attempt: Int, delayMillis: Long) = Unit
    fun immediateReconnectRequested(attempt: Int) = Unit
    fun staleConnectionDisposed(connectionId: String?, reasonCode: String) = Unit
}

object NoOpSignalRLogger : SignalRLogger {
    override fun connected(serverHost: String, connectionId: String, reconnectAttempts: Int) = Unit
    override fun disconnected(connectionId: String?, failure: SignalRTransportException) = Unit
    override fun reconnecting(serverHost: String, attempt: Int, delayMillis: Long, reasonCode: String) = Unit
    override fun heartbeat(connectionId: String, snapshotVersion: Long) = Unit
}
