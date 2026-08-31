package com.ashraffarag.sentricam.live.presentation

import com.ashraffarag.sentricam.live.domain.LiveSessionState

enum class LiveStatusKind {
    IDLE,
    CONNECTING,
    STREAMING,
    RECONNECTING,
    FAILED,
}

data class LiveStatusPresentation(
    val kind: LiveStatusKind,
    val errorCode: String? = null,
)

object LiveStatusPresenter {
    fun present(state: LiveSessionState): LiveStatusPresentation = when (state) {
        LiveSessionState.Idle -> LiveStatusPresentation(LiveStatusKind.IDLE)
        is LiveSessionState.Connecting -> LiveStatusPresentation(LiveStatusKind.CONNECTING)
        is LiveSessionState.Negotiating -> LiveStatusPresentation(LiveStatusKind.CONNECTING)
        is LiveSessionState.Connected -> LiveStatusPresentation(LiveStatusKind.STREAMING)
        is LiveSessionState.Buffering -> LiveStatusPresentation(LiveStatusKind.RECONNECTING)
        is LiveSessionState.Failed -> LiveStatusPresentation(LiveStatusKind.FAILED, state.code)
    }
}
