package com.ashraffarag.sentricam.device.connectivity

import com.ashraffarag.sentricam.device.domain.ConnectivityState
import kotlinx.coroutines.flow.StateFlow

interface ConnectivityProvider {
    val state: StateFlow<ConnectivityState>
}
