package com.botglobal.mobile.platform.identity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class FederatedIdentityProvider { Google, Apple }

enum class FederatedCredentialType { IdToken }

data class FederatedAccountKey(
    val provider: FederatedIdentityProvider,
    val providerSubject: String,
) {
    init {
        require(providerSubject.isNotBlank()) { "Provider subject must not be blank." }
    }
}

class FederatedCredential(
    val provider: FederatedIdentityProvider,
    val type: FederatedCredentialType,
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Federated credential must not be blank." }
    }

    override fun toString(): String = "FederatedCredential(provider=$provider,type=$type,value=<redacted>)"
}

sealed interface FederatedCredentialResult {
    data class Acquired(val credential: FederatedCredential) : FederatedCredentialResult
    data object Cancelled : FederatedCredentialResult
    data object ConfigurationMissing : FederatedCredentialResult
    data object Unavailable : FederatedCredentialResult
    data object Failed : FederatedCredentialResult
}

interface FederatedCredentialProvider {
    suspend fun acquire(provider: FederatedIdentityProvider): FederatedCredentialResult
}

object UnavailableFederatedCredentialProvider : FederatedCredentialProvider {
    override suspend fun acquire(provider: FederatedIdentityProvider) = FederatedCredentialResult.Unavailable
}

sealed interface FederatedSignInResult {
    data class Authenticated(val session: MobileSession) : FederatedSignInResult
    data object Rejected : FederatedSignInResult
    data object AccountLinkRequired : FederatedSignInResult
    data object ConfigurationMissing : FederatedSignInResult
    data object NetworkFailure : FederatedSignInResult
    data object Failed : FederatedSignInResult
}

interface FederatedIdentityGateway {
    suspend fun restore(): MobileSession?
    suspend fun authenticate(credential: FederatedCredential): FederatedSignInResult
    suspend fun logout()
}

object UnavailableFederatedIdentityGateway : FederatedIdentityGateway {
    override suspend fun restore(): MobileSession? = null
    override suspend fun authenticate(credential: FederatedCredential) = FederatedSignInResult.ConfigurationMissing
    override suspend fun logout() = Unit
}

enum class FederatedAuthenticationError {
    ConfigurationMissing,
    ProviderUnavailable,
    ProviderFailure,
    BackendRejected,
    AccountLinkRequired,
    NetworkFailure,
    AuthenticationFailure,
}

sealed interface FederatedAuthenticationState {
    data object RestoringSession : FederatedAuthenticationState
    data object SignedOut : FederatedAuthenticationState
    data class SigningIn(val provider: FederatedIdentityProvider) : FederatedAuthenticationState
    data class SignedIn(val session: MobileSession) : FederatedAuthenticationState
    data class AuthenticationError(val reason: FederatedAuthenticationError) : FederatedAuthenticationState
}

class FederatedIdentityController(
    private val credentials: FederatedCredentialProvider,
    private val gateway: FederatedIdentityGateway,
) {
    private val mutableState = MutableStateFlow<FederatedAuthenticationState>(
        FederatedAuthenticationState.RestoringSession,
    )
    val state: StateFlow<FederatedAuthenticationState> = mutableState.asStateFlow()

    suspend fun restore() {
        mutableState.value = FederatedAuthenticationState.RestoringSession
        mutableState.value = runCatching { gateway.restore() }
            .fold(
                onSuccess = { session ->
                    session?.let(FederatedAuthenticationState::SignedIn)
                        ?: FederatedAuthenticationState.SignedOut
                },
                onFailure = {
                    FederatedAuthenticationState.AuthenticationError(
                        FederatedAuthenticationError.NetworkFailure,
                    )
                },
            )
    }

    suspend fun signIn(provider: FederatedIdentityProvider) {
        mutableState.value = FederatedAuthenticationState.SigningIn(provider)
        when (val acquired = credentials.acquire(provider)) {
            is FederatedCredentialResult.Acquired -> authenticate(acquired.credential)
            FederatedCredentialResult.Cancelled -> mutableState.value = FederatedAuthenticationState.SignedOut
            FederatedCredentialResult.ConfigurationMissing -> error(FederatedAuthenticationError.ConfigurationMissing)
            FederatedCredentialResult.Unavailable -> error(FederatedAuthenticationError.ProviderUnavailable)
            FederatedCredentialResult.Failed -> error(FederatedAuthenticationError.ProviderFailure)
        }
    }

    suspend fun logout() {
        runCatching { gateway.logout() }
        mutableState.value = FederatedAuthenticationState.SignedOut
    }

    fun dismissError() {
        if (mutableState.value is FederatedAuthenticationState.AuthenticationError) {
            mutableState.value = FederatedAuthenticationState.SignedOut
        }
    }

    private suspend fun authenticate(credential: FederatedCredential) {
        when (val result = gateway.authenticate(credential)) {
            is FederatedSignInResult.Authenticated -> mutableState.value =
                FederatedAuthenticationState.SignedIn(result.session)
            FederatedSignInResult.Rejected -> error(FederatedAuthenticationError.BackendRejected)
            FederatedSignInResult.AccountLinkRequired -> error(FederatedAuthenticationError.AccountLinkRequired)
            FederatedSignInResult.ConfigurationMissing -> error(FederatedAuthenticationError.ConfigurationMissing)
            FederatedSignInResult.NetworkFailure -> error(FederatedAuthenticationError.NetworkFailure)
            FederatedSignInResult.Failed -> error(FederatedAuthenticationError.AuthenticationFailure)
        }
    }

    private fun error(reason: FederatedAuthenticationError) {
        mutableState.value = FederatedAuthenticationState.AuthenticationError(reason)
    }
}
