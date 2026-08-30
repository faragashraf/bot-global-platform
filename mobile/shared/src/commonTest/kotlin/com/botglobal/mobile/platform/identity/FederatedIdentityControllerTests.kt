package com.botglobal.mobile.platform.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FederatedIdentityControllerTests {
    @Test
    fun signedOutCanAuthenticateOnlyAfterAuthoritativeBackendSuccess() = runTest {
        val gateway = FakeGateway(result = FederatedSignInResult.Authenticated(session()))
        val controller = FederatedIdentityController(FakeCredentials(), gateway)
        controller.restore()
        controller.signIn(FederatedIdentityProvider.Google)

        assertTrue(controller.state.value is FederatedAuthenticationState.SignedIn)
        assertEquals("provider-token", gateway.received?.value)
        assertEquals("platform-access", (controller.state.value as FederatedAuthenticationState.SignedIn).session.accessToken)
    }

    @Test
    fun cancellationRemainsSignedOut() = runTest {
        val controller = FederatedIdentityController(FakeCredentials(FederatedCredentialResult.Cancelled), FakeGateway())
        controller.restore()
        controller.signIn(FederatedIdentityProvider.Google)
        assertEquals(FederatedAuthenticationState.SignedOut, controller.state.value)
    }

    @Test
    fun malformedCredentialCannotBeCreated() {
        val result = runCatching {
            FederatedCredential(FederatedIdentityProvider.Google, FederatedCredentialType.IdToken, " ")
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun backendRejectionAndFailureNeverAuthenticate() = runTest {
        for (result in listOf(FederatedSignInResult.Rejected, FederatedSignInResult.Failed)) {
            val controller = FederatedIdentityController(FakeCredentials(), FakeGateway(result = result))
            controller.restore()
            controller.signIn(FederatedIdentityProvider.Google)
            assertFalse(controller.state.value is FederatedAuthenticationState.SignedIn)
        }
    }

    @Test
    fun providerSubjectIsIndependentFromEmailAndCredentialIsRedacted() {
        val key1 = FederatedAccountKey(FederatedIdentityProvider.Google, "subject-a")
        val key2 = FederatedAccountKey(FederatedIdentityProvider.Google, "subject-b")
        assertFalse(key1 == key2)
        val credential = FederatedCredential(FederatedIdentityProvider.Google, FederatedCredentialType.IdToken, "secret")
        assertFalse(credential.toString().contains("secret"))
    }

    @Test
    fun restoreBypassesChooserAndLogoutReturnsSignedOut() = runTest {
        val credentials = FakeCredentials()
        val controller = FederatedIdentityController(credentials, FakeGateway(restored = session()))
        controller.restore()
        assertTrue(controller.state.value is FederatedAuthenticationState.SignedIn)
        assertEquals(0, credentials.acquisitions)
        controller.logout()
        assertEquals(FederatedAuthenticationState.SignedOut, controller.state.value)
    }

    @Test
    fun sessionRestoreNetworkFailureIsVisibleAndNeverAuthenticates() = runTest {
        val gateway = object : FederatedIdentityGateway {
            override suspend fun restore(): MobileSession? = error("network")
            override suspend fun authenticate(credential: FederatedCredential) = FederatedSignInResult.Failed
            override suspend fun logout() = Unit
        }
        val controller = FederatedIdentityController(FakeCredentials(), gateway)

        controller.restore()

        assertEquals(
            FederatedAuthenticationState.AuthenticationError(FederatedAuthenticationError.NetworkFailure),
            controller.state.value,
        )
    }

    private class FakeCredentials(
        private val result: FederatedCredentialResult = FederatedCredentialResult.Acquired(
            FederatedCredential(FederatedIdentityProvider.Google, FederatedCredentialType.IdToken, "provider-token"),
        ),
    ) : FederatedCredentialProvider {
        var acquisitions = 0
        override suspend fun acquire(provider: FederatedIdentityProvider): FederatedCredentialResult {
            acquisitions++
            return result
        }
    }

    private class FakeGateway(
        private val restored: MobileSession? = null,
        private val result: FederatedSignInResult = FederatedSignInResult.Rejected,
    ) : FederatedIdentityGateway {
        var received: FederatedCredential? = null
        override suspend fun restore() = restored
        override suspend fun authenticate(credential: FederatedCredential): FederatedSignInResult {
            received = credential
            return result
        }
        override suspend fun logout() = Unit
    }

    private fun session() = MobileSession(
        "platform-access", "expiry", "platform-refresh", "refresh-expiry",
        ApplicationIdentity("membership", "subject", "User", IdentityKind.Registered, "nqrb"),
    )
}
