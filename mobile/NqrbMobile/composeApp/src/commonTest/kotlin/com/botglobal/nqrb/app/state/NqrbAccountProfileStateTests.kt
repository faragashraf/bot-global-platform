package com.botglobal.nqrb.app.state

import com.botglobal.mobile.platform.identity.ApplicationIdentity
import com.botglobal.mobile.platform.identity.FederatedAuthenticationState
import com.botglobal.mobile.platform.identity.FederatedCredential
import com.botglobal.mobile.platform.identity.FederatedIdentityController
import com.botglobal.mobile.platform.identity.FederatedIdentityGateway
import com.botglobal.mobile.platform.identity.FederatedIdentityProvider
import com.botglobal.mobile.platform.identity.FederatedSignInResult
import com.botglobal.mobile.platform.identity.IdentityKind
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.identity.UnavailableFederatedCredentialProvider
import com.botglobal.nqrb.app.data.NqrbAccountProfileResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NqrbAccountProfileStateTests {
    @Test
    fun expiredProfileAuthenticationHidesIdentityAndReturnsToSignIn() = runTest {
        val identity = FederatedIdentityController(
            UnavailableFederatedCredentialProvider,
            RestoringIdentityGateway(session()),
        )
        val state = NqrbAppState(
            identity = identity,
            accountProfile = { NqrbAccountProfileResult.AuthenticationRequired },
            callActionScope = backgroundScope,
        )

        state.startup()

        assertIs<FederatedAuthenticationState.SignedOut>(identity.state.value)
        assertIs<NqrbAccountProfileState.Hidden>(state.accountProfileState.value)
        assertEquals(NqrbDestination.SignIn, state.navigation.current)
    }

    private class RestoringIdentityGateway(private val restored: MobileSession) : FederatedIdentityGateway {
        override suspend fun restore() = restored
        override suspend fun authenticate(credential: FederatedCredential) = FederatedSignInResult.Failed
        override suspend fun logout() = Unit
    }

    private fun session() = MobileSession(
        "access", "2099-01-01T00:00:00Z", "refresh", "2099-02-01T00:00:00Z",
        ApplicationIdentity("membership", "subject", "Person", IdentityKind.Registered, "nqrb"),
    )
}
