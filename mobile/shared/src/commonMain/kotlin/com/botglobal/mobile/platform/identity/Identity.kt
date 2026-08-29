package com.botglobal.mobile.platform.identity

enum class IdentityKind { Guest, Registered }

data class ApplicationIdentity(
    val membershipId: String,
    val subjectId: String,
    val displayName: String,
    val kind: IdentityKind,
    val applicationKey: String,
)

data class MobileSession(
    val accessToken: String,
    val accessExpiresAtUtc: String,
    val refreshToken: String,
    val refreshExpiresAtUtc: String,
    val identity: ApplicationIdentity,
)

interface SessionVault {
    suspend fun restore(): MobileSession?
    suspend fun save(session: MobileSession)
    suspend fun clear()
}

interface BiometricSessionGate {
    val isAvailable: Boolean
    suspend fun unlock(reason: String): BiometricResult
}

sealed interface BiometricResult {
    data object Succeeded : BiometricResult
    data object Cancelled : BiometricResult
    data class Failed(val reason: String? = null) : BiometricResult
    data object Unavailable : BiometricResult
}
