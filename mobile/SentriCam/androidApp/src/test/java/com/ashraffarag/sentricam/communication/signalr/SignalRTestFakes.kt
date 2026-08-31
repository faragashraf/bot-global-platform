package com.ashraffarag.sentricam.communication.signalr

import com.ashraffarag.sentricam.device.domain.DeviceClock
import com.ashraffarag.sentricam.device.domain.DeviceIdentity
import com.ashraffarag.sentricam.device.domain.DevicePlatform
import com.ashraffarag.sentricam.device.domain.DeviceState
import com.ashraffarag.sentricam.device.domain.DeviceStatusMapper
import com.ashraffarag.sentricam.device.registration.DeviceCredentialStore
import com.ashraffarag.sentricam.device.registration.DeviceCredentials
import com.ashraffarag.sentricam.device.registration.RegistrationDetails
import kotlinx.coroutines.flow.MutableStateFlow
import com.ashraffarag.sentricam.live.capability.LiveSignalReceiver
import com.ashraffarag.sentricam.live.domain.LiveDeviceCapabilities
import com.ashraffarag.sentricam.live.domain.LiveIceCandidate
import com.ashraffarag.sentricam.live.domain.LiveSessionDescription
import com.ashraffarag.sentricam.live.domain.LiveSessionStatistics
import com.ashraffarag.sentricam.live.domain.LiveSessionStatusUpdate
import com.ashraffarag.sentricam.cameracontrol.capability.CameraControlSignalReceiver
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlCommandResult
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlDeviceReport

internal open class FakeSignalRClient(
    private val id: String,
    private val connectFailure: Throwable? = null,
) : SignalRClient {
    var connectCalls = 0
    var heartbeatCalls = 0
    var transportPulseCalls = 0
    var disconnectCalls = 0
    var closeCalls = 0
    var cameraControlReportCalls = 0
    var transportPulseFailure: Throwable? = null
    var onClose: (() -> Unit)? = null
    val operationalHealthReports = mutableListOf<DeviceOperationalHealthReport>()
    var onOperationalHealthReport: ((DeviceOperationalHealthReport) -> Unit)? = null
    var observedToken: String? = null
    var lastHeartbeat: SignalRHeartbeat? = null
    val completedCommands = mutableListOf<RemoteDeviceCommandResult>()
    val outboundEvents = mutableListOf<String>()
    private var lifecycle: ConnectionLifecycle? = null
    private var commandReceiver: SignalRCommandReceiver? = null
    private var pairingRevocationReceiver: PairingRevocationReceiver? = null

    override open suspend fun connect(
        configuration: SignalRConfiguration,
        accessTokenProvider: SignalRAccessTokenProvider,
        lifecycle: ConnectionLifecycle,
        commandReceiver: SignalRCommandReceiver,
        pairingRevocationReceiver: PairingRevocationReceiver,
        liveReceiver: LiveSignalReceiver,
        cameraControlReceiver: CameraControlSignalReceiver,
    ): SignalRConnection {
        connectCalls++
        observedToken = accessTokenProvider.accessToken()
        connectFailure?.let { throw it }
        this.lifecycle = lifecycle
        this.commandReceiver = commandReceiver
        this.pairingRevocationReceiver = pairingRevocationReceiver
        return SignalRConnection(id)
    }

    override suspend fun completeCommand(result: RemoteDeviceCommandResult) {
        completedCommands += result
        outboundEvents += "command"
    }

    override suspend fun reportLiveCapabilities(capabilities: LiveDeviceCapabilities) = Unit
    override suspend fun submitLiveAnswer(answer: LiveSessionDescription) = Unit
    override suspend fun submitLiveCandidate(candidate: LiveIceCandidate) = Unit
    override suspend fun reportLiveState(update: LiveSessionStatusUpdate) = Unit
    override suspend fun reportLiveStatistics(statistics: LiveSessionStatistics) = Unit
    override suspend fun reportCameraControlState(report: CameraControlDeviceReport) { cameraControlReportCalls++ }
    override suspend fun reportOperationalHealth(report: DeviceOperationalHealthReport) {
        operationalHealthReports += report
        onOperationalHealthReport?.invoke(report)
    }
    override suspend fun completeCameraControlCommand(result: CameraControlCommandResult) = Unit

    override suspend fun heartbeat(heartbeat: SignalRHeartbeat): SignalRHeartbeatAcknowledgement {
        heartbeatCalls++
        lastHeartbeat = heartbeat
        outboundEvents += "heartbeat"
        return SignalRHeartbeatAcknowledgement(
            heartbeat.deviceId,
            heartbeat.connectionId,
            heartbeat.snapshotVersion,
            heartbeat.timestamp,
            heartbeat.timestamp,
        )
    }

    override suspend fun transportPulse() {
        transportPulseCalls++
        transportPulseFailure?.let { throw it }
    }

    override suspend fun disconnect() {
        disconnectCalls++
    }

    override fun close() {
        closeCalls++
        onClose?.invoke()
    }

    fun closeFromServer(cause: Throwable? = null) {
        lifecycle?.closed(cause)
    }

    fun receive(command: RemoteDeviceCommand) {
        commandReceiver?.received(command)
    }

    fun revokePairing(deviceId: String = SIGNALR_SERVER_DEVICE_ID) {
        pairingRevocationReceiver?.revoked(
            DevicePairingRevoked(deviceId, "2026-08-02T00:00:00Z", "administrator_removed"),
        )
    }
}

internal class FakeSignalRNetworkMonitor(initiallyOnline: Boolean) : SignalRNetworkMonitor {
    override val state = MutableStateFlow(
        if (initiallyOnline) SignalRNetworkState.Validated else SignalRNetworkState.Unavailable,
    )

    fun unavailable() {
        state.value = SignalRNetworkState.Unavailable
    }

    fun availableNotValidated() {
        state.value = SignalRNetworkState.Available
    }

    fun validated() {
        state.value = SignalRNetworkState.Validated
    }
}

internal class FakeSignalRClock(var value: Long = SIGNALR_NOW) : SignalRClock {
    override fun nowMillis(): Long = value++
}

internal class FakeSignalRCredentialStore(
    token: String = SIGNALR_TOKEN,
    expiration: Long = SIGNALR_EXPIRATION,
) : DeviceCredentialStore {
    var value: DeviceCredentials? = DeviceCredentials(
        RegistrationDetails(
            serverBaseUrl = "https://server.example/",
            serverDeviceId = SIGNALR_SERVER_DEVICE_ID,
            accessTokenExpiresAtMillis = expiration,
        ),
        token,
    )

    override fun load(): DeviceCredentials? = value
    override fun save(credentials: DeviceCredentials) {
        value = credentials
    }
    override fun clear() {
        value = null
    }
}

internal class FakeSignalRServerConfiguration(
    private var url: String = "https://server.example/",
    override val isDebug: Boolean = false,
) : com.ashraffarag.sentricam.device.registration.ServerConfiguration {
    override fun baseUrl(): String = url
    override fun updateBaseUrl(normalizedUrl: String): Boolean {
        url = normalizedUrl
        return true
    }
}

internal fun signalRTestSnapshot() = DeviceStatusMapper(DeviceClock { SIGNALR_NOW }).map(
    DeviceState(
        DeviceIdentity(
            deviceId = "1cf83aa2-bd35-4299-8fe3-55d4fe75d6b8",
            friendlyName = "Entry",
            platform = DevicePlatform.ANDROID,
            appVersion = "1.0",
            deviceModel = "Pixel",
            androidVersion = "16",
            buildFingerprint = null,
            installedAtMillis = 1L,
            lastStartupAtMillis = 2L,
        ),
    ),
)

internal const val SIGNALR_SERVER_DEVICE_ID = "a8b861db-0608-4fe2-891d-cf4fc01d39f4"
internal const val SIGNALR_TOKEN = "test-token-never-log"
internal const val SIGNALR_NOW = 1_785_499_200_000L
internal const val SIGNALR_EXPIRATION = SIGNALR_NOW + 3_600_000L
