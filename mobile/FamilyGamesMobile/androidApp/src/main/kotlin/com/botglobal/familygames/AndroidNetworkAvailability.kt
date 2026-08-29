package com.botglobal.familygames

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.botglobal.mobile.platform.realtime.NetworkAvailability
import com.botglobal.mobile.platform.realtime.NetworkAvailabilityState
import com.botglobal.mobile.platform.realtime.stabilizedNetworkAvailability
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicLong

class AndroidNetworkAvailability(context: Context) : NetworkAvailability {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val observerGeneration = NextObserverGeneration.incrementAndGet()

    private val rawChanges: Flow<NetworkAvailabilityState> = callbackFlow {
        val observedCapabilities = mutableMapOf<Network, NetworkCapabilities>()
        val observedCapabilitiesLock = Any()

        fun publishCurrentAvailability() {
            val activeNetwork = connectivity.activeNetwork
            val capabilities = activeNetwork?.let(connectivity::getNetworkCapabilities)
            val activeNetworkIsValidated = capabilities.hasValidatedInternet()
            val vpnHasValidatedUnderlyingTransport = if (
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            ) {
                synchronized(observedCapabilitiesLock) {
                    observedCapabilities.any { (network, candidate) ->
                        network != activeNetwork && candidate.hasValidatedInternet() &&
                            !candidate.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    }
                }
            } else {
                true
            }
            val state = if (activeNetworkIsValidated && vpnHasValidatedUnderlyingTransport) {
                NetworkAvailabilityState.Available
            } else {
                NetworkAvailabilityState.Unavailable
            }
            trySend(state)
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivity.getNetworkCapabilities(network)?.let { capabilities ->
                    synchronized(observedCapabilitiesLock) {
                        observedCapabilities[network] = capabilities
                    }
                }
                publishCurrentAvailability()
            }

            override fun onLost(network: Network) {
                synchronized(observedCapabilitiesLock) {
                    observedCapabilities.remove(network)
                }
                publishCurrentAvailability()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                synchronized(observedCapabilitiesLock) {
                    observedCapabilities[network] = capabilities
                }
                publishCurrentAvailability()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity.registerNetworkCallback(request, callback)
        publishCurrentAvailability()
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }

    override val changes = stabilizedNetworkAvailability(
        rawChanges = rawChanges,
        observerGeneration = observerGeneration,
        unavailableConfirmationMillis = UnavailableConfirmationMillis,
    ).onEach { snapshot ->
        Log.i(
            LogTag,
            "connectivity ${snapshot.state.name.lowercase()} " +
                "observerGeneration=${snapshot.observerGeneration} networkRevision=${snapshot.revision}",
        )
    }

    private companion object {
        const val UnavailableConfirmationMillis = 750L
        const val LogTag = "LammaConnectivity"
        val NextObserverGeneration = AtomicLong(0L)
    }
}

private fun NetworkCapabilities?.hasValidatedInternet(): Boolean =
    this?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
