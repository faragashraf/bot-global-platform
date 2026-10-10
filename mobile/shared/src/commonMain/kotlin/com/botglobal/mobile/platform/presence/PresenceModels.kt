package com.botglobal.mobile.platform.presence

enum class PresenceEvidence { Connected, Disconnected, Unknown }

data class PresenceAccount(
    val applicationKey: String,
    val membershipId: String,
    val subjectId: String,
    val credentialGeneration: String = "",
) {
    val stableKey: String get() = "$applicationKey\n$membershipId\n$subjectId\n$credentialGeneration"
}

data class PresenceLease(
    val leaseId: String,
    val customToken: String,
    val databaseUrl: String,
    val connectionPath: String,
    val expiresAtEpochMillis: Long,
    val heartbeatSeconds: Int,
    val freshnessSeconds: Int,
)

enum class PresenceLifecycleState { Disabled, Idle, Connecting, Connected, Suspended, Unknown, Failed }

data class PresenceSnapshot(
    val accountKey: String? = null,
    val state: PresenceLifecycleState = PresenceLifecycleState.Idle,
    val generation: Long = 0,
    val lastObservedAtEpochMillis: Long? = null,
    val safeError: String? = null,
)

sealed interface PresenceGatewayEvent {
    val generation: Long
    data class Connected(override val generation: Long, val observedAtEpochMillis: Long) : PresenceGatewayEvent
    data class Unknown(override val generation: Long, val reason: String) : PresenceGatewayEvent
    data class Failed(override val generation: Long, val reason: String) : PresenceGatewayEvent
}
