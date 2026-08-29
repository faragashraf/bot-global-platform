package com.botglobal.familygames

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.botglobal.mobile.platform.realtime.NetworkAvailability
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class AndroidNetworkAvailability(context: Context) : NetworkAvailability {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override val changes: Flow<Boolean> = callbackFlow {
        fun publishCurrentAvailability() {
            val available = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            trySend(available)
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publishCurrentAvailability()
            override fun onLost(network: Network) = publishCurrentAvailability()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                publishCurrentAvailability()
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity.registerNetworkCallback(request, callback)
        publishCurrentAvailability()
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
