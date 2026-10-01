package com.ashraffarag.sentricam.communication.signalr.android

import android.util.Log
import com.ashraffarag.sentricam.BuildConfig
import com.ashraffarag.sentricam.communication.signalr.SignalRLogger
import com.ashraffarag.sentricam.communication.signalr.SignalRTransportException
import com.ashraffarag.sentricam.device.android.RuntimeLifecycleDiagnostics

class AndroidSignalRLogger(
    private val deviceId: () -> String? = { null },
    private val networkAvailable: () -> Boolean = { false },
) : SignalRLogger {
    private fun context() =
        "deviceId=${deviceId() ?: "unknown"} networkAvailable=${networkAvailable()} " +
            "appState=${RuntimeLifecycleDiagnostics.appState} serviceState=${RuntimeLifecycleDiagnostics.serviceState}"

    override fun connected(serverHost: String, connectionId: String, reconnectAttempts: Int) {
        Log.i(TAG, "event=connected host=$serverHost connectionId=$connectionId retries=$reconnectAttempts ${context()}")
    }

    override fun disconnected(connectionId: String?, failure: SignalRTransportException) {
        Log.i(
            TAG,
            "event=disconnected connectionId=${connectionId ?: "none"} reason=${failure.failureCode.stableCode} ${context()}",
        )
        if (!BuildConfig.DEBUG) return

        val diagnosticContext = failure.diagnostics
        val causes = generateSequence(failure as Throwable?) { it.cause }
            .mapIndexed { index, cause ->
                "$index:${cause.javaClass.name}:${cause.message.orEmpty().replace('\n', ' ')}"
            }
            .joinToString(" <- ")
        Log.e(
            TAG,
            "realtime_diagnostic phase=${diagnosticContext?.phase ?: "unknown"} " +
                "state=${diagnosticContext?.connectionState ?: "unknown"} " +
                "transport=${diagnosticContext?.transport ?: "automatic"} " +
                "protocol=${diagnosticContext?.protocol ?: "json"} " +
                "lastInbound=${diagnosticContext?.lastInboundEvent ?: "none"} " +
                "lastOutbound=${diagnosticContext?.lastOutboundInvocation ?: "none"} ${context()} causes=$causes",
            failure,
        )
    }

    override fun reconnecting(
        serverHost: String,
        attempt: Int,
        delayMillis: Long,
        reasonCode: String,
    ) {
        Log.i(TAG, "event=reconnecting host=$serverHost attempt=$attempt delayMs=$delayMillis reason=$reasonCode ${context()}")
    }

    override fun heartbeat(connectionId: String, snapshotVersion: Long) {
        Log.d(TAG, "event=heartbeat connectionId=$connectionId snapshot=$snapshotVersion ${context()}")
    }

    override fun pendingBackoffCancelled(attempt: Int, delayMillis: Long) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "event=pending_backoff_canceled attempt=$attempt delayMs=$delayMillis ${context()}")
        }
    }

    override fun immediateReconnectRequested(attempt: Int) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "event=immediate_reconnect_requested attempt=$attempt ${context()}")
        }
    }

    override fun staleConnectionDisposed(connectionId: String?, reasonCode: String) {
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "event=stale_connection_disposed connectionId=${connectionId ?: "none"} reason=$reasonCode ${context()}",
            )
        }
    }

    private companion object {
        const val TAG = "SignalRConnection"
    }
}
