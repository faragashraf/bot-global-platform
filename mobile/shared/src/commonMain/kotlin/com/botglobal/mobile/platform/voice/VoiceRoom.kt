package com.botglobal.mobile.platform.voice

data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

interface IceServerProvider {
    suspend fun servers(): List<IceServer>
}

enum class VoiceRoomState { Idle, Joining, Connected, Reconnecting, Failed, Unavailable }

interface VoiceRoomController {
    val state: VoiceRoomState
    val muted: Boolean
    suspend fun join(roomId: String)
    suspend fun leave()
    suspend fun setMuted(muted: Boolean)
}

sealed interface VoiceSignal {
    val roomId: String
    data class Offer(override val roomId: String, val sessionDescription: String) : VoiceSignal
    data class Answer(override val roomId: String, val sessionDescription: String) : VoiceSignal
    data class IceCandidate(override val roomId: String, val candidate: String) : VoiceSignal
    data class Presence(override val roomId: String, val participantId: String, val joined: Boolean) : VoiceSignal
    data class MuteState(override val roomId: String, val participantId: String, val muted: Boolean) : VoiceSignal
}
