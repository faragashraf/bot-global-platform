package com.botglobal.mobile.platform.calling

import com.botglobal.mobile.platform.voice.VoiceRoomSnapshot

/** WebRTC audio RTP payload counters; excludes signaling, FCM, auth, and OS/IP overhead. */
class CallNetworkUsageAccumulator {
    private var generation: Long? = null
    private var baselineSent = 0L
    private var baselineReceived = 0L
    private var lastSent = 0L
    private var lastReceived = 0L
    private var completedSent = 0L
    private var completedReceived = 0L
    private var available = false
    private var connectedAtEpochMillis: Long? = null
    private var activeSegmentStartedAtEpochMillis: Long? = null
    private var completedConnectedMillis = 0L
    private var final: CallNetworkUsage? = null

    fun reset() {
        generation = null
        baselineSent = 0
        baselineReceived = 0
        lastSent = 0
        lastReceived = 0
        completedSent = 0
        completedReceived = 0
        available = false
        connectedAtEpochMillis = null
        activeSegmentStartedAtEpochMillis = null
        completedConnectedMillis = 0
        final = null
    }

    /** Starts a connected-media interval; reconnects preserve the first connection timestamp. */
    fun markConnected(atEpochMillis: Long) {
        if (final != null || activeSegmentStartedAtEpochMillis != null) return
        if (connectedAtEpochMillis == null) connectedAtEpochMillis = atEpochMillis
        activeSegmentStartedAtEpochMillis = atEpochMillis
    }

    /** Ends only the current connected-media interval, excluding reconnect downtime from duration. */
    fun markMediaInactive(atEpochMillis: Long) {
        if (final != null) return
        freezeConnectedSegment(atEpochMillis)
    }

    fun update(snapshot: VoiceRoomSnapshot): CallNetworkUsage {
        final?.let { return it }
        val stats = snapshot.stats
        if (!stats.available) return current()
        if (generation != snapshot.generation) {
            freezeSegment()
            generation = snapshot.generation
            baselineSent = stats.outboundBytes.coerceAtLeast(0)
            baselineReceived = stats.inboundBytes.coerceAtLeast(0)
        } else {
            if (stats.outboundBytes < lastSent) { completedSent += (lastSent - baselineSent).coerceAtLeast(0); baselineSent = stats.outboundBytes.coerceAtLeast(0) }
            if (stats.inboundBytes < lastReceived) { completedReceived += (lastReceived - baselineReceived).coerceAtLeast(0); baselineReceived = stats.inboundBytes.coerceAtLeast(0) }
        }
        lastSent = stats.outboundBytes.coerceAtLeast(0)
        lastReceived = stats.inboundBytes.coerceAtLeast(0)
        available = true
        return current()
    }

    fun finish(endedAtEpochMillis: Long? = null): CallNetworkUsage {
        final?.let { return it }
        freezeSegment()
        endedAtEpochMillis?.let(::freezeConnectedSegment)
        val connectedAt = connectedAtEpochMillis
        val connectedDurationSeconds = if (connectedAt != null && endedAtEpochMillis != null) {
            (completedConnectedMillis / 1_000).coerceAtLeast(0)
        } else {
            null
        }
        return CallNetworkUsage(
            bytesSent = completedSent,
            bytesReceived = completedReceived,
            measurementAvailable = available,
            isFinal = true,
            connectedAtEpochMillis = connectedAt,
            endedAtEpochMillis = endedAtEpochMillis,
            connectedDurationSeconds = connectedDurationSeconds,
        ).also { final = it }
    }

    private fun freezeSegment() {
        if (generation != null) {
            completedSent += (lastSent - baselineSent).coerceAtLeast(0)
            completedReceived += (lastReceived - baselineReceived).coerceAtLeast(0)
        }
    }

    private fun freezeConnectedSegment(atEpochMillis: Long) {
        val startedAt = activeSegmentStartedAtEpochMillis ?: return
        completedConnectedMillis += (atEpochMillis - startedAt).coerceAtLeast(0)
        activeSegmentStartedAtEpochMillis = null
    }

    private fun current() = CallNetworkUsage(
        bytesSent = completedSent + (lastSent - baselineSent).coerceAtLeast(0),
        bytesReceived = completedReceived + (lastReceived - baselineReceived).coerceAtLeast(0),
        measurementAvailable = available,
        connectedAtEpochMillis = connectedAtEpochMillis,
    )
}
