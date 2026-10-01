package com.ashraffarag.sentricam.communication.signalr.android

import com.ashraffarag.sentricam.communication.signalr.SignalRNetworkState
import com.ashraffarag.sentricam.device.domain.ConnectivityState
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidSignalRNetworkMonitorTest {
    @Test
    fun onlyAndroidValidatedConnectivityIsUsableForRealtime() {
        assertEquals(SignalRNetworkState.Unavailable, ConnectivityState.Offline.toSignalRNetworkState())
        assertEquals(
            SignalRNetworkState.Available,
            ConnectivityState.LocalNetwork(setOf("wifi")).toSignalRNetworkState(),
        )
        assertEquals(
            SignalRNetworkState.Available,
            ConnectivityState.Limited("internet_not_validated").toSignalRNetworkState(),
        )
        assertEquals(SignalRNetworkState.Available, ConnectivityState.CaptivePortal.toSignalRNetworkState())
        assertEquals(
            SignalRNetworkState.Validated,
            ConnectivityState.InternetAvailable(setOf("wifi")).toSignalRNetworkState(),
        )
    }
}
