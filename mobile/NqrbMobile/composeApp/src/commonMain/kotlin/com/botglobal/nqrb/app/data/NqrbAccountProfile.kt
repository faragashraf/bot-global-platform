package com.botglobal.nqrb.app.data

data class NqrbAccountProfile(
    val displayName: String,
    val email: String,
)

sealed interface NqrbAccountProfileResult {
    data class Available(val profile: NqrbAccountProfile) : NqrbAccountProfileResult
    data object AuthenticationRequired : NqrbAccountProfileResult
    data object RetryableFailure : NqrbAccountProfileResult
}

fun interface NqrbAccountProfileGateway {
    suspend fun load(): NqrbAccountProfileResult
}

object UnavailableNqrbAccountProfileGateway : NqrbAccountProfileGateway {
    override suspend fun load() = NqrbAccountProfileResult.RetryableFailure
}
