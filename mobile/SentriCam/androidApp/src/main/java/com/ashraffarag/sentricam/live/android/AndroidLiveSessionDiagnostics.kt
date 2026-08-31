package com.ashraffarag.sentricam.live.android

import com.ashraffarag.sentricam.live.capability.LiveSessionDiagnostics

class AndroidLiveSessionDiagnostics(
    private val deviceId: () -> String? = { null },
    private val connectionId: () -> String? = { null },
) : LiveSessionDiagnostics {
    override fun event(name: String, sessionId: String?, detail: String) {
        LiveViewDiagnostics.log(LiveViewDiagnostics.WEBRTC) {
            "event=$name deviceId=${deviceId() ?: "unknown"} connectionId=${connectionId() ?: "none"} " +
                "liveSessionId=${sessionId ?: "none"} $detail"
        }
    }
}
