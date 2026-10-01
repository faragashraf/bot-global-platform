package com.ashraffarag.sentricam.communication.signalr

import com.microsoft.signalr.HttpRequestException
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import com.ashraffarag.sentricam.live.capability.LiveSignalReceiver
import com.ashraffarag.sentricam.live.domain.LiveDeviceCapabilities
import com.ashraffarag.sentricam.live.domain.LiveIceCandidate
import com.ashraffarag.sentricam.live.domain.LivePreviewVisibility
import com.ashraffarag.sentricam.live.domain.LiveSessionAssignment
import com.ashraffarag.sentricam.live.domain.LiveSessionDescription
import com.ashraffarag.sentricam.live.domain.LiveSessionStatistics
import com.ashraffarag.sentricam.live.domain.LiveSessionStatusUpdate
import com.ashraffarag.sentricam.cameracontrol.capability.CameraControlSignalReceiver
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlCancellation
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlCommandEnvelope
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlCommandResult
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlDeviceReport

class MicrosoftSignalRClient : SignalRClient {
    private val lock = Any()
    private var hubConnection: HubConnection? = null
    private var closing = false
    @Volatile private var phase = PHASE_IDLE
    @Volatile private var lastInboundEvent: String? = null
    @Volatile private var lastOutboundInvocation: String? = null

    override suspend fun connect(
        configuration: SignalRConfiguration,
        accessTokenProvider: SignalRAccessTokenProvider,
        lifecycle: ConnectionLifecycle,
        commandReceiver: SignalRCommandReceiver,
        pairingRevocationReceiver: PairingRevocationReceiver,
        liveReceiver: LiveSignalReceiver,
        cameraControlReceiver: CameraControlSignalReceiver,
    ): SignalRConnection {
        phase = PHASE_NEGOTIATE_HANDSHAKE
        lastInboundEvent = null
        lastOutboundInvocation = null
        val connection = HubConnectionBuilder.create(configuration.hubUrl)
            .withAccessTokenProvider(Single.defer { Single.fromCallable(accessTokenProvider::accessToken) })
            .withServerTimeout(configuration.serverTimeoutMillis)
            .withKeepAliveInterval(configuration.keepAliveIntervalMillis)
            .build()
        synchronized(lock) {
            check(hubConnection == null) { "SignalR client is already connected or connecting" }
            closing = false
            hubConnection = connection
        }
        connection.onClosed { cause ->
            if (!synchronized(lock) { closing }) lifecycle.closed(cause)
        }
        connection.on(
            COMMAND_METHOD,
            { command -> inbound(COMMAND_METHOD) { commandReceiver.received(command) } },
            RemoteDeviceCommand::class.java,
        )
        connection.on(
            PAIRING_REVOKED_METHOD,
            { value -> inbound(PAIRING_REVOKED_METHOD) { pairingRevocationReceiver.revoked(value) } },
            DevicePairingRevoked::class.java,
        )
        connection.on(BEGIN_LIVE_METHOD, { value -> inbound(BEGIN_LIVE_METHOD) { liveReceiver.begin(value) } }, LiveSessionAssignment::class.java)
        connection.on(LIVE_OFFER_METHOD, { value -> inbound(LIVE_OFFER_METHOD) { liveReceiver.offer(value) } }, LiveSessionDescription::class.java)
        connection.on(LIVE_CANDIDATE_METHOD, { value -> inbound(LIVE_CANDIDATE_METHOD) { liveReceiver.candidate(value) } }, LiveIceCandidate::class.java)
        connection.on(
            LIVE_PREVIEW_METHOD,
            { value: LivePreviewVisibility -> inbound(LIVE_PREVIEW_METHOD) { liveReceiver.preview(value.visible) } },
            LivePreviewVisibility::class.java,
        )
        connection.on(END_LIVE_METHOD, { value -> inbound(END_LIVE_METHOD) { liveReceiver.end(value) } }, LiveSessionStatusUpdate::class.java)
        connection.on(
            BROWSER_LIVE_STATISTICS_METHOD,
            { value -> inbound(BROWSER_LIVE_STATISTICS_METHOD) { liveReceiver.browserStatistics(value) } },
            LiveSessionStatistics::class.java,
        )
        connection.on(
            CAMERA_CONTROL_COMMAND_METHOD,
            { value -> inbound(CAMERA_CONTROL_COMMAND_METHOD) { cameraControlReceiver.receive(value) } },
            CameraControlCommandEnvelope::class.java,
        )
        connection.on(
            CAMERA_CONTROL_CANCEL_METHOD,
            { value -> inbound(CAMERA_CONTROL_CANCEL_METHOD) { cameraControlReceiver.cancel(value) } },
            CameraControlCancellation::class.java,
        )
        try {
            connection.start().awaitCompletion()
            phase = PHASE_CONNECTED
            val connectionId = connection.connectionId?.takeIf(String::isNotBlank)
                ?: throw transportFailure(SignalRFailureCode.PROTOCOL_ERROR)
            return SignalRConnection(connectionId)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SignalRTransportException) {
            throw exception
        } catch (exception: HttpRequestException) {
            val code = if (exception.statusCode == 401 || exception.statusCode == 403) {
                SignalRFailureCode.AUTHENTICATION_FAILED
            } else {
                SignalRFailureCode.SERVER_UNAVAILABLE
            }
            throw transportFailure(code, exception)
        } catch (exception: Exception) {
            throw transportFailure(SignalRFailureCode.SERVER_UNAVAILABLE, exception)
        }
    }

    override suspend fun heartbeat(heartbeat: SignalRHeartbeat): SignalRHeartbeatAcknowledgement {
        val connection = synchronized(lock) { hubConnection }
            ?: throw SignalRTransportException(SignalRFailureCode.CONNECTION_CLOSED)
        return try {
            lastOutboundInvocation = HEARTBEAT_METHOD
            phase = PHASE_INVOCATION
            connection.invoke(
                SignalRHeartbeatAcknowledgement::class.java,
                HEARTBEAT_METHOD,
                heartbeat,
            ).awaitValue().also { phase = PHASE_CONNECTED }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SignalRTransportException) {
            throw exception
        } catch (exception: Exception) {
            throw transportFailure(SignalRFailureCode.HEARTBEAT_FAILED, exception)
        }
    }

    override suspend fun transportPulse() = invoke(TRANSPORT_PULSE_METHOD)

    override suspend fun completeCommand(result: RemoteDeviceCommandResult) =
        invoke(COMMAND_COMPLETED_METHOD, result)

    override suspend fun reportLiveCapabilities(capabilities: LiveDeviceCapabilities) =
        invoke(REPORT_LIVE_CAPABILITIES_METHOD, capabilities)

    override suspend fun submitLiveAnswer(answer: LiveSessionDescription) =
        invoke(SUBMIT_LIVE_ANSWER_METHOD, answer)

    override suspend fun submitLiveCandidate(candidate: LiveIceCandidate) =
        invoke(SUBMIT_LIVE_CANDIDATE_METHOD, candidate)

    override suspend fun reportLiveState(update: LiveSessionStatusUpdate) =
        invoke(REPORT_LIVE_STATE_METHOD, update)

    override suspend fun reportLiveStatistics(statistics: LiveSessionStatistics) =
        invoke(REPORT_LIVE_STATISTICS_METHOD, statistics)

    override suspend fun reportCameraControlState(report: CameraControlDeviceReport) =
        invoke(REPORT_CAMERA_CONTROL_STATE_METHOD, report)

    override suspend fun reportOperationalHealth(report: DeviceOperationalHealthReport) =
        invoke(REPORT_OPERATIONAL_HEALTH_METHOD, report)

    override suspend fun completeCameraControlCommand(result: CameraControlCommandResult) =
        invoke(COMPLETE_CAMERA_CONTROL_COMMAND_METHOD, result)

    private suspend fun invoke(method: String, payload: Any) {
        val connection = synchronized(lock) { hubConnection }
            ?: throw SignalRTransportException(SignalRFailureCode.CONNECTION_CLOSED)
        try {
            lastOutboundInvocation = method
            phase = PHASE_INVOCATION
            connection.invoke(method, payload).awaitCompletion()
            phase = PHASE_CONNECTED
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SignalRTransportException) {
            throw exception
        } catch (exception: Exception) {
            throw transportFailure(classifyInvocationFailure(exception, connection.connectionState?.name), exception)
        }
    }

    private suspend fun invoke(method: String) {
        val connection = synchronized(lock) { hubConnection }
            ?: throw SignalRTransportException(SignalRFailureCode.CONNECTION_CLOSED)
        try {
            lastOutboundInvocation = method
            phase = PHASE_INVOCATION
            connection.invoke(method).awaitCompletion()
            phase = PHASE_CONNECTED
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SignalRTransportException) {
            throw exception
        } catch (exception: Exception) {
            throw transportFailure(classifyInvocationFailure(exception, connection.connectionState?.name), exception)
        }
    }

    private fun inbound(event: String, action: () -> Unit) {
        lastInboundEvent = event
        phase = PHASE_HANDLER
        try {
            action()
        } finally {
            phase = PHASE_CONNECTED
        }
    }

    private fun transportFailure(
        code: SignalRFailureCode,
        cause: Throwable? = null,
    ) = SignalRTransportException(
        code,
        cause,
        SignalRProtocolDiagnostics(
            phase = phase,
            connectionState = synchronized(lock) { hubConnection?.connectionState?.name ?: "NONE" },
            lastInboundEvent = lastInboundEvent,
            lastOutboundInvocation = lastOutboundInvocation,
        ),
    )

    override suspend fun disconnect() {
        val connection = synchronized(lock) {
            closing = true
            hubConnection.also { hubConnection = null }
        } ?: return
        try {
            connection.stop().awaitCompletion()
        } finally {
            connection.close()
        }
    }

    override fun close() {
        val connection = synchronized(lock) {
            closing = true
            hubConnection.also { hubConnection = null }
        }
        connection?.close()
    }

    private suspend fun Completable.awaitCompletion() = suspendCancellableCoroutine { continuation ->
        val disposable = subscribe(
            { if (continuation.isActive) continuation.resume(Unit) },
            { failure -> if (continuation.isActive) continuation.resumeWithException(failure) },
        )
        continuation.invokeOnCancellation { disposable.dispose() }
    }

    private suspend fun <T : Any> Single<T>.awaitValue(): T = suspendCancellableCoroutine { continuation ->
        val disposable = subscribe(
            { value -> if (continuation.isActive) continuation.resume(value) },
            { failure -> if (continuation.isActive) continuation.resumeWithException(failure) },
        )
        continuation.invokeOnCancellation { disposable.dispose() }
    }

    private companion object {
        const val PHASE_IDLE = "idle"
        const val PHASE_NEGOTIATE_HANDSHAKE = "negotiate_or_handshake"
        const val PHASE_CONNECTED = "connected"
        const val PHASE_INVOCATION = "outbound_invocation"
        const val PHASE_HANDLER = "handler_execution"
        const val HEARTBEAT_METHOD = "Heartbeat"
        const val TRANSPORT_PULSE_METHOD = "TransportPulse"
        const val COMMAND_METHOD = "ReceiveCommand"
        const val PAIRING_REVOKED_METHOD = "PairingRevoked"
        const val COMMAND_COMPLETED_METHOD = "CommandCompleted"
        const val BEGIN_LIVE_METHOD = "BeginLiveSession"
        const val LIVE_OFFER_METHOD = "ReceiveLiveOffer"
        const val LIVE_CANDIDATE_METHOD = "ReceiveLiveIceCandidate"
        const val LIVE_PREVIEW_METHOD = "SetLivePreviewVisibility"
        const val END_LIVE_METHOD = "EndLiveSession"
        const val BROWSER_LIVE_STATISTICS_METHOD = "BrowserLiveStatistics"
        const val REPORT_LIVE_CAPABILITIES_METHOD = "ReportLiveCapabilities"
        const val SUBMIT_LIVE_ANSWER_METHOD = "SubmitLiveAnswer"
        const val SUBMIT_LIVE_CANDIDATE_METHOD = "SubmitLiveIceCandidate"
        const val REPORT_LIVE_STATE_METHOD = "ReportLiveSessionState"
        const val REPORT_LIVE_STATISTICS_METHOD = "ReportLiveStatistics"
        const val CAMERA_CONTROL_COMMAND_METHOD = "ReceiveCameraControlCommand"
        const val CAMERA_CONTROL_CANCEL_METHOD = "CancelCameraControlCommand"
        const val REPORT_CAMERA_CONTROL_STATE_METHOD = "ReportCameraControlState"
        const val REPORT_OPERATIONAL_HEALTH_METHOD = "ReportOperationalHealth"
        const val COMPLETE_CAMERA_CONTROL_COMMAND_METHOD = "CompleteCameraControlCommand"
    }
}

internal fun classifyInvocationFailure(
    failure: Throwable,
    connectionState: String?,
): SignalRFailureCode {
    if (failure is SignalRTransportException) return failure.failureCode
    if (!connectionState.equals("CONNECTED", ignoreCase = true)) {
        return SignalRFailureCode.CONNECTION_CLOSED
    }

    val contractFailure = generateSequence(failure as Throwable?) { it.cause }.any { cause ->
        val type = cause.javaClass.name.lowercase()
        val message = cause.message.orEmpty().lowercase()
        type.contains("json") ||
            type.contains("serialization") ||
            message.contains("deserialize") ||
            message.contains("deserializ") ||
            message.contains("binding failure") ||
            message.contains("invalid payload")
    }
    if (contractFailure) return SignalRFailureCode.CONTRACT_ERROR

    val transportInterrupted = generateSequence(failure as Throwable?) { it.cause }.any { cause ->
        val type = cause.javaClass.name.lowercase()
        val message = cause.message.orEmpty().lowercase()
        cause is java.io.IOException ||
            type.contains("socketexception") ||
            type.contains("websocket") ||
            type.contains("closedchannelexception") ||
            message.contains("software caused connection abort") ||
            message.contains("connection reset") ||
            message.contains("broken pipe") ||
            message.contains("network is unreachable") ||
            message.contains("socket closed") ||
            message.contains("websocket is closed") ||
            message.contains("connection was closed") ||
            message.contains("connection is closed")
    }
    return if (transportInterrupted) {
        SignalRFailureCode.CONNECTION_CLOSED
    } else {
        SignalRFailureCode.PROTOCOL_ERROR
    }
}
