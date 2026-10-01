package com.ashraffarag.sentricam.communication.signalr.presentation

import com.ashraffarag.sentricam.communication.signalr.SignalRConnectionMetrics
import com.ashraffarag.sentricam.communication.signalr.SignalRFailureCode
import com.ashraffarag.sentricam.communication.signalr.SignalRState
import com.ashraffarag.sentricam.device.domain.CameraOperatingMode
import java.net.URI

enum class RealtimeStatusKind {
    LOCAL_CAMERA,
    CONNECTED,
    CONNECTING,
    RECONNECTING,
    OFFLINE,
    SERVER_UNREACHABLE,
    AUTHENTICATION_FAILED,
    PROTOCOL_ERROR,
    SERVICE_STOPPED,
}

data class RealtimeStatusPresentation(
    val kind: RealtimeStatusKind,
    val hubHost: String,
    val lastConnectedAtMillis: Long?,
    val reconnectAttempt: Int,
    val reasonCode: String?,
    val canReconnectNow: Boolean,
)

object RealtimeStatusPresenter {
    fun present(
        state: SignalRState,
        metrics: SignalRConnectionMetrics,
        hubUrl: String,
        operatingMode: CameraOperatingMode,
    ): RealtimeStatusPresentation {
        val kind = if (operatingMode is CameraOperatingMode.Standalone) {
            RealtimeStatusKind.LOCAL_CAMERA
        } else when (state) {
            SignalRState.Stopped -> RealtimeStatusKind.SERVICE_STOPPED
            is SignalRState.Connecting -> RealtimeStatusKind.CONNECTING
            is SignalRState.Connected -> RealtimeStatusKind.CONNECTED
            is SignalRState.Reconnecting -> RealtimeStatusKind.RECONNECTING
            is SignalRState.AuthenticationFailed -> RealtimeStatusKind.AUTHENTICATION_FAILED
            is SignalRState.ServerUnavailable -> RealtimeStatusKind.SERVER_UNREACHABLE
            is SignalRState.Disconnected -> if (
                state.reasonCode == SignalRFailureCode.NETWORK_OFFLINE.stableCode
            ) RealtimeStatusKind.OFFLINE else RealtimeStatusKind.SERVER_UNREACHABLE
            is SignalRState.Error -> if (
                state.reasonCode == SignalRFailureCode.CONTRACT_ERROR.stableCode
            ) RealtimeStatusKind.PROTOCOL_ERROR else RealtimeStatusKind.SERVER_UNREACHABLE
        }
        val reason = when (state) {
            is SignalRState.Disconnected -> state.reasonCode
            is SignalRState.Reconnecting -> state.reasonCode
            is SignalRState.AuthenticationFailed -> state.reasonCode
            is SignalRState.ServerUnavailable -> state.reasonCode
            is SignalRState.Error -> state.reasonCode
            else -> null
        }
        return RealtimeStatusPresentation(
            kind = kind,
            hubHost = runCatching { URI(hubUrl).host }.getOrNull().orEmpty()
                .ifBlank { hubUrl.ifBlank { "—" } },
            lastConnectedAtMillis = metrics.connectedAtMillis,
            reconnectAttempt = state.reconnectAttempts,
            reasonCode = reason,
            canReconnectNow = kind !in setOf(
                RealtimeStatusKind.LOCAL_CAMERA,
                RealtimeStatusKind.CONNECTED,
                RealtimeStatusKind.CONNECTING,
            ),
        )
    }
}
