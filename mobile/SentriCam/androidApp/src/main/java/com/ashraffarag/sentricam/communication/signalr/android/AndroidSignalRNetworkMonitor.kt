package com.ashraffarag.sentricam.communication.signalr.android

import com.ashraffarag.sentricam.communication.signalr.SignalRNetworkMonitor
import com.ashraffarag.sentricam.communication.signalr.SignalRNetworkState
import com.ashraffarag.sentricam.device.domain.ConnectivityState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class AndroidSignalRNetworkMonitor(
    connectivity: StateFlow<ConnectivityState>,
    scope: CoroutineScope,
) : SignalRNetworkMonitor {
    override val state: StateFlow<SignalRNetworkState> = connectivity
        .map(ConnectivityState::toSignalRNetworkState)
        .stateIn(scope, SharingStarted.Eagerly, connectivity.value.toSignalRNetworkState())
}

internal fun ConnectivityState.toSignalRNetworkState(): SignalRNetworkState = when (this) {
    ConnectivityState.Offline -> SignalRNetworkState.Unavailable
    is ConnectivityState.InternetAvailable -> SignalRNetworkState.Validated
    is ConnectivityState.LocalNetwork,
    is ConnectivityState.Limited,
    ConnectivityState.CaptivePortal,
    -> SignalRNetworkState.Available
}
