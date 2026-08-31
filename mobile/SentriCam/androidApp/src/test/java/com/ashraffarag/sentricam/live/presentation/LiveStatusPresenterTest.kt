package com.ashraffarag.sentricam.live.presentation

import com.ashraffarag.sentricam.live.domain.LiveSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveStatusPresenterTest {
    @Test
    fun mapsInternalTransportStateToTheFiveUserFacingLiveStates() {
        val values = listOf(
            LiveSessionState.Idle to LiveStatusKind.IDLE,
            LiveSessionState.Connecting("session") to LiveStatusKind.CONNECTING,
            LiveSessionState.Negotiating("session") to LiveStatusKind.CONNECTING,
            LiveSessionState.Connected("session") to LiveStatusKind.STREAMING,
            LiveSessionState.Buffering("session") to LiveStatusKind.RECONNECTING,
            LiveSessionState.Failed("session", "webrtc_connection_failed") to LiveStatusKind.FAILED,
        )

        values.forEach { (state, expected) ->
            assertEquals(expected, LiveStatusPresenter.present(state).kind)
        }
        assertNull(LiveStatusPresenter.present(LiveSessionState.Idle).errorCode)
        assertEquals(
            "webrtc_connection_failed",
            LiveStatusPresenter.present(
                LiveSessionState.Failed("session", "webrtc_connection_failed"),
            ).errorCode,
        )
    }
}
