package com.botglobal.lamma.app.state

import com.botglobal.lamma.app.data.GameSessionSnapshot

enum class OpponentConnectionState {
    Unknown,
    Connected,
    Disconnected,
}

/** Generic game-session presence; it deliberately has no XO board dependency. */
fun GameSessionSnapshot.opponentConnectionState(
    localMembershipId: String?,
): OpponentConnectionState {
    if (localMembershipId == null) return OpponentConnectionState.Unknown
    val opponent = players.firstOrNull { it.membershipId != localMembershipId }
        ?: return OpponentConnectionState.Unknown
    return if (opponent.isConnected) {
        OpponentConnectionState.Connected
    } else {
        OpponentConnectionState.Disconnected
    }
}
