package com.ashraffarag.sentricam.device.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import com.ashraffarag.sentricam.BuildConfig
import com.ashraffarag.sentricam.device.connectivity.ConnectivityProvider
import com.ashraffarag.sentricam.device.domain.ConnectivityState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidConnectivityProvider(context: Context) : ConnectivityProvider, AutoCloseable {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val mutableState = MutableStateFlow(readCurrentState())
    override val state: StateFlow<ConnectivityState> = mutableState.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val next = stateFor(network)
            publish("network_available", network, next)
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val next = capabilities.toState()
            val wasValidated = mutableState.value is ConnectivityState.InternetAvailable
            publish("network_capabilities_changed", network, next)
            if (next is ConnectivityState.InternetAvailable) {
                log(
                    if (wasValidated) "duplicate_reconnect_suppressed" else "network_validated",
                    network,
                    next,
                )
            }
        }
        override fun onLost(network: Network) {
            publish("network_lost", network, readCurrentState())
        }
    }

    init {
        manager?.let { connectivityManager ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(callback)
            } else {
                connectivityManager.registerNetworkCallback(
                    NetworkRequest.Builder().build(),
                    callback,
                )
            }
        }
    }

    override fun close() {
        runCatching { manager?.unregisterNetworkCallback(callback) }
    }

    private fun stateFor(network: Network): ConnectivityState =
        manager?.getNetworkCapabilities(network)?.toState() ?: readCurrentState()

    private fun publish(event: String, network: Network, next: ConnectivityState) {
        mutableState.value = next
        log(event, network, next)
        if (event == "network_available" && next is ConnectivityState.InternetAvailable) {
            log("network_validated", network, next)
        }
    }

    private fun log(event: String, network: Network, state: ConnectivityState) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            TAG,
            "event=$event network=$network available=${state.networkAvailable} " +
                "validated=${state is ConnectivityState.InternetAvailable} state=${state.javaClass.simpleName}",
        )
    }

    private fun readCurrentState(): ConnectivityState {
        val currentManager = manager ?: return ConnectivityState.Offline
        val network = currentManager.activeNetwork ?: return ConnectivityState.Offline
        return currentManager.getNetworkCapabilities(network)?.toState() ?: ConnectivityState.Offline
    }

    private fun NetworkCapabilities.toState(): ConnectivityState {
        val transports = buildSet {
            if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
        }
        return when {
            hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) -> ConnectivityState.CaptivePortal
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> ConnectivityState.InternetAvailable(
                transports = transports,
                metered = !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            )
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ->
                ConnectivityState.Limited("internet_not_validated")
            else -> ConnectivityState.LocalNetwork(transports)
        }
    }

    private companion object {
        const val TAG = "ConnectivityObserver"
    }
}
