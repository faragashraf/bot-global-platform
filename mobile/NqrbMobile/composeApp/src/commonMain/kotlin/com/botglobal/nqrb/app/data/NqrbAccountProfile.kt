package com.botglobal.nqrb.app.data

import com.botglobal.mobile.platform.identity.MobileSession

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
    suspend fun load(session: MobileSession): NqrbAccountProfileResult
}

object UnavailableNqrbAccountProfileGateway : NqrbAccountProfileGateway {
    override suspend fun load(session: MobileSession) = NqrbAccountProfileResult.RetryableFailure
}
