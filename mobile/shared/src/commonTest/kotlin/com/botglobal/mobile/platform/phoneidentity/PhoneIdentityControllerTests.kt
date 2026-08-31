package com.botglobal.mobile.platform.phoneidentity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PhoneIdentityControllerTests {
    @Test
    fun discoveryClassifiesZeroOneAndMultipleUsableCandidates() {
        val controller = PhoneIdentityController()
        controller.applyDiscovery(PhoneIdentityDiscoveryResult.NoCandidates)
        assertEquals(PhoneIdentityStatus.ManualEntryRequired, controller.state.value.status)

        controller.applyDiscovery(PhoneIdentityDiscoveryResult.Candidates(listOf(candidate("+201012345678"))))
        assertEquals(PhoneIdentityStatus.CandidateFound, controller.state.value.status)
        assertEquals("+201012345678", controller.state.value.selected?.number?.canonical)

        controller.applyDiscovery(
            PhoneIdentityDiscoveryResult.Candidates(
                listOf(candidate("+201012345678"), candidate("+447700900123")),
            ),
        )
        assertEquals(PhoneIdentityStatus.MultipleCandidates, controller.state.value.status)
        assertNull(controller.state.value.selected)
    }

    @Test
    fun emptyAndMalformedCandidatesAreIgnoredBeforeStateResolution() {
        val valid = candidate("+201012345678")
        val duplicate = valid.copy(source = PhoneIdentitySource.Esim)
        val controller = PhoneIdentityController()

        controller.applyDiscovery(PhoneIdentityDiscoveryResult.Candidates(listOf(valid, duplicate)))

        assertEquals(PhoneIdentityStatus.CandidateFound, controller.state.value.status)
        assertEquals(1, controller.state.value.candidates.size)
        assertNull(E164PhoneNumber.parse(""))
        assertNull(E164PhoneNumber.parse("01012345678"))
        assertNull(E164PhoneNumber.parse("+20-not-a-number"))
        assertNull(E164PhoneNumber.parse("+٢٠١٠١٢٣٤٥٦٧٨"))
    }

    @Test
    fun discoveredAndManualNumbersRemainClassifiedBelowVerifiedTrust() {
        val controller = PhoneIdentityController()
        val discovered = candidate("+201012345678")
        controller.applyDiscovery(PhoneIdentityDiscoveryResult.Candidates(listOf(discovered)))
        assertTrue(controller.selectCandidate(discovered.number))
        assertEquals(PhoneIdentityStatus.Selected, controller.state.value.status)
        assertEquals(PhoneIdentityTrust.SimAssociated, controller.state.value.selected?.trust)

        controller.requireManualEntry()
        assertTrue(controller.submitManualNumber("+44 7700 900123"))
        assertEquals(PhoneIdentityStatus.Selected, controller.state.value.status)
        assertEquals(PhoneIdentityTrust.SelfDeclared, controller.state.value.selected?.trust)
        assertNotEquals(PhoneIdentityStatus.Verified, controller.state.value.status)
    }

    @Test
    fun onlyGatewayVerifiedResultTransitionsToVerified() = runTest {
        val verified = requireNotNull(E164PhoneNumber.parse("+201012345678"))
        val controller = PhoneIdentityController(
            verification = FakeVerificationGateway(PhoneIdentityVerificationResult.Verified(verified)),
        )

        controller.restore()

        assertEquals(PhoneIdentityStatus.Verified, controller.state.value.status)
        assertEquals(PhoneIdentitySource.RestoredServerIdentity, controller.state.value.selected?.source)
    }

    @Test
    fun requiredPolicyBlocksActivationButOptionalPolicyDoesNot() {
        val unverified = PhoneIdentityState(status = PhoneIdentityStatus.VerificationRequired)
        val verified = PhoneIdentityState(status = PhoneIdentityStatus.Verified)

        assertFalse(PhoneIdentityPolicy(PhoneIdentityRequirement.RequiredForActivation).allowsActivation(unverified))
        assertTrue(PhoneIdentityPolicy(PhoneIdentityRequirement.RequiredForActivation).allowsActivation(verified))
        assertTrue(PhoneIdentityPolicy(PhoneIdentityRequirement.Optional).allowsActivation(unverified))
    }

    @Test
    fun maskingNeverReturnsTheFullNumberAndHandlesInvalidInputSafely() {
        val full = "+201012345678"
        val masked = maskPhoneNumber(full)

        assertNotEquals(full, masked)
        assertFalse(masked.contains("1012345678"))
        assertEquals("+20 •••• 5678", masked)
        assertEquals("••••", maskPhoneNumber("123"))
        assertEquals("••••", maskPhoneNumber("not-a-number"))
    }

    private fun candidate(number: String) = PhoneIdentityCandidate(
        number = requireNotNull(E164PhoneNumber.parse(number)),
        source = PhoneIdentitySource.Sim,
    )

    private class FakeVerificationGateway(
        private val restored: PhoneIdentityVerificationResult,
    ) : PhoneIdentityVerificationGateway {
        override suspend fun start(number: E164PhoneNumber) = PhoneIdentityVerificationResult.Unavailable
        override suspend fun confirm(challenge: PhoneIdentityVerificationChallenge, code: String) =
            PhoneIdentityVerificationResult.Unavailable
        override suspend fun restore() = restored
        override suspend fun clear() = Unit
    }
}
