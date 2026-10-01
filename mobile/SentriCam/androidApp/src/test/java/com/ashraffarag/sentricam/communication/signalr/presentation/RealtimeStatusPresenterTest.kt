package com.ashraffarag.sentricam.communication.signalr.presentation

import com.ashraffarag.sentricam.communication.signalr.SignalRConnectionMetrics
import com.ashraffarag.sentricam.communication.signalr.SignalRFailureCode
import com.ashraffarag.sentricam.communication.signalr.SignalRState
import com.ashraffarag.sentricam.device.domain.CameraOperatingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeStatusPresenterTest {
    @Test
    fun connectedPresentationIncludesSafeHubAndLastConnection() {
        val presentation = RealtimeStatusPresenter.present(
            SignalRState.Connected("connection-id", 100L, reconnectAttempts = 2),
            SignalRConnectionMetrics(connectedAtMillis = 100L, reconnectAttempts = 2),
            "https://hub.example/hubs/device",
            CameraOperatingMode.HubManaged("device-id"),
        )

        assertEquals(RealtimeStatusKind.CONNECTED, presentation.kind)
        assertEquals("hub.example", presentation.hubHost)
        assertEquals(100L, presentation.lastConnectedAtMillis)
        assertEquals(2, presentation.reconnectAttempt)
        assertFalse(presentation.canReconnectNow)
    }

    @Test
    fun operationalFailuresRemainDistinctAndReconnectable() {
        val cases = listOf(
            SignalRState.Disconnected(SignalRFailureCode.NETWORK_OFFLINE.stableCode) to RealtimeStatusKind.OFFLINE,
            SignalRState.ServerUnavailable("server_unavailable", 5) to RealtimeStatusKind.SERVER_UNREACHABLE,
            SignalRState.AuthenticationFailed("authentication_failed", 0) to RealtimeStatusKind.AUTHENTICATION_FAILED,
            SignalRState.Error("contract_error", 0) to RealtimeStatusKind.PROTOCOL_ERROR,
            SignalRState.Reconnecting(3, 200L, "connection_closed") to RealtimeStatusKind.RECONNECTING,
        )

        cases.forEach { (state, expected) ->
            val presentation = RealtimeStatusPresenter.present(
                state,
                SignalRConnectionMetrics(connectedAtMillis = 50L),
                "http://192.168.1.14:5173/hubs/device",
                CameraOperatingMode.HubManaged("device-id"),
            )
            assertEquals(expected, presentation.kind)
            assertTrue(presentation.canReconnectNow)
        }
    }

    @Test
    fun stoppedServiceIsNotPresentedAsLiveOrRecordingFailure() {
        val presentation = RealtimeStatusPresenter.present(
            SignalRState.Stopped,
            SignalRConnectionMetrics(),
            "",
            CameraOperatingMode.HubManaged("device-id"),
        )

        assertEquals(RealtimeStatusKind.SERVICE_STOPPED, presentation.kind)
        assertTrue(presentation.canReconnectNow)
    }

    @Test
    fun standaloneIsPresentedAsHealthyLocalCameraInsteadOfActionRequired() {
        val presentation = RealtimeStatusPresenter.present(
            SignalRState.Stopped,
            SignalRConnectionMetrics(),
            "",
            CameraOperatingMode.Standalone,
        )

        assertEquals(RealtimeStatusKind.LOCAL_CAMERA, presentation.kind)
        assertFalse(presentation.canReconnectNow)
    }

    @Test
    fun hubFailureDoesNotChangeHubManagedPresentation() {
        val presentation = RealtimeStatusPresenter.present(
            SignalRState.ServerUnavailable("server_unavailable", 3),
            SignalRConnectionMetrics(reconnectAttempts = 3),
            "https://hub.example/",
            CameraOperatingMode.HubManaged("device-id"),
        )

        assertEquals(RealtimeStatusKind.SERVER_UNREACHABLE, presentation.kind)
        assertTrue(presentation.canReconnectNow)
    }
}
