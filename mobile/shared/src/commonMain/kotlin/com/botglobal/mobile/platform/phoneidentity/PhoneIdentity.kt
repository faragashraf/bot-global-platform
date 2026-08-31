package com.botglobal.mobile.platform.phoneidentity

import kotlin.jvm.JvmInline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@JvmInline
value class E164PhoneNumber private constructor(val canonical: String) {
    val masked: String get() = maskPhoneNumber(canonical)

    override fun toString(): String = masked

    companion object {
        fun parse(input: String): E164PhoneNumber? {
            val normalized = input.trim().filterNot { it.isWhitespace() || it in "-()" }
            if (!normalized.startsWith('+')) return null
            val digits = normalized.drop(1)
            if (digits.length !in 8..15 || digits.firstOrNull() == '0' || !digits.all { it in '0'..'9' }) return null
            return E164PhoneNumber("+$digits")
        }
    }
}

fun maskPhoneNumber(value: String): String {
    val canonical = E164PhoneNumber.parse(value)?.canonical ?: return "••••"
    val digits = canonical.drop(1)
    val prefix = digits.take(2)
    val suffix = digits.takeLast(4)
    return "+$prefix •••• $suffix"
}

enum class PhoneIdentitySource {
    Sim,
    Esim,
    SystemPhoneNumberApi,
    PhoneNumberHint,
    ManualEntry,
    RestoredServerIdentity,
}

enum class PhoneIdentityTrust {
    SelfDeclared,
    DeviceDetected,
    SimAssociated,
    Verified,
}

private fun PhoneIdentitySource.defaultTrust(): PhoneIdentityTrust = when (this) {
    PhoneIdentitySource.Sim,
    PhoneIdentitySource.Esim,
    -> PhoneIdentityTrust.SimAssociated
    PhoneIdentitySource.SystemPhoneNumberApi,
    PhoneIdentitySource.PhoneNumberHint,
    -> PhoneIdentityTrust.DeviceDetected
    PhoneIdentitySource.ManualEntry -> PhoneIdentityTrust.SelfDeclared
    PhoneIdentitySource.RestoredServerIdentity -> PhoneIdentityTrust.Verified
}

data class PhoneIdentityCandidate(
    val number: E164PhoneNumber,
    val source: PhoneIdentitySource,
    val lineLabel: String? = null,
    val carrierLabel: String? = null,
    val trust: PhoneIdentityTrust = source.defaultTrust(),
)

enum class PhoneIdentityDiscoveryUnavailableReason {
    UnsupportedDevice,
    PermissionUnavailable,
    PermissionDenied,
    PermissionPermanentlyDenied,
    PlatformFailure,
}

sealed interface PhoneIdentityDiscoveryResult {
    data class Candidates(val values: List<PhoneIdentityCandidate>) : PhoneIdentityDiscoveryResult
    data object NoCandidates : PhoneIdentityDiscoveryResult
    data class Unavailable(
        val reason: PhoneIdentityDiscoveryUnavailableReason,
    ) : PhoneIdentityDiscoveryResult
}

interface PhoneIdentityDiscovery {
    suspend fun discoverAfterExplanation(): PhoneIdentityDiscoveryResult
}

object UnavailablePhoneIdentityDiscovery : PhoneIdentityDiscovery {
    override suspend fun discoverAfterExplanation() = PhoneIdentityDiscoveryResult.Unavailable(
        PhoneIdentityDiscoveryUnavailableReason.UnsupportedDevice,
    )
}

enum class PhoneIdentityStatus {
    Unknown,
    DiscoveryAvailable,
    CandidateFound,
    MultipleCandidates,
    Selected,
    ManualEntryRequired,
    VerificationRequired,
    VerificationPending,
    Verified,
    VerificationFailed,
    Unavailable,
}

enum class PhoneIdentityFailure {
    InvalidInternationalNumber,
    VerificationUnavailable,
    VerificationRejected,
}

data class PhoneIdentityState(
    val status: PhoneIdentityStatus = PhoneIdentityStatus.Unknown,
    val candidates: List<PhoneIdentityCandidate> = emptyList(),
    val selected: PhoneIdentityCandidate? = null,
    val discoveryUnavailableReason: PhoneIdentityDiscoveryUnavailableReason? = null,
    val failure: PhoneIdentityFailure? = null,
)

enum class PhoneIdentityRequirement {
    NotRequired,
    Optional,
    RequiredForFeature,
    RequiredForActivation,
}

data class PhoneIdentityPolicy(
    val requirement: PhoneIdentityRequirement,
) {
    fun allowsActivation(state: PhoneIdentityState): Boolean =
        requirement != PhoneIdentityRequirement.RequiredForActivation || state.status == PhoneIdentityStatus.Verified
}

data class PhoneIdentityVerificationChallenge(val challengeId: String)

sealed interface PhoneIdentityVerificationResult {
    data class Pending(val challenge: PhoneIdentityVerificationChallenge) : PhoneIdentityVerificationResult
    data class Verified(val number: E164PhoneNumber) : PhoneIdentityVerificationResult
    data object Rejected : PhoneIdentityVerificationResult
    data object Unavailable : PhoneIdentityVerificationResult
    data object NotFound : PhoneIdentityVerificationResult
}

interface PhoneIdentityVerificationGateway {
    suspend fun start(number: E164PhoneNumber): PhoneIdentityVerificationResult
    suspend fun confirm(challenge: PhoneIdentityVerificationChallenge, code: String): PhoneIdentityVerificationResult
    suspend fun restore(): PhoneIdentityVerificationResult
    suspend fun clear()
}

object UnavailablePhoneIdentityVerificationGateway : PhoneIdentityVerificationGateway {
    override suspend fun start(number: E164PhoneNumber) = PhoneIdentityVerificationResult.Unavailable
    override suspend fun confirm(challenge: PhoneIdentityVerificationChallenge, code: String) =
        PhoneIdentityVerificationResult.Unavailable
    override suspend fun restore() = PhoneIdentityVerificationResult.NotFound
    override suspend fun clear() = Unit
}

class PhoneIdentityController(
    private val discovery: PhoneIdentityDiscovery = UnavailablePhoneIdentityDiscovery,
    private val verification: PhoneIdentityVerificationGateway = UnavailablePhoneIdentityVerificationGateway,
) {
    private val mutableState = MutableStateFlow(PhoneIdentityState())
    val state: StateFlow<PhoneIdentityState> = mutableState.asStateFlow()

    fun discoveryAvailable() {
        if (mutableState.value.status == PhoneIdentityStatus.Unknown) {
            mutableState.value = PhoneIdentityState(status = PhoneIdentityStatus.DiscoveryAvailable)
        }
    }

    suspend fun discover() {
        applyDiscovery(discovery.discoverAfterExplanation())
    }

    fun applyDiscovery(result: PhoneIdentityDiscoveryResult) {
        mutableState.value = when (result) {
            is PhoneIdentityDiscoveryResult.Candidates -> {
                val candidates = result.values.distinctBy { it.number.canonical }
                when (candidates.size) {
                    0 -> PhoneIdentityState(status = PhoneIdentityStatus.ManualEntryRequired)
                    1 -> PhoneIdentityState(
                        status = PhoneIdentityStatus.CandidateFound,
                        candidates = candidates,
                        selected = candidates.single(),
                    )
                    else -> PhoneIdentityState(
                        status = PhoneIdentityStatus.MultipleCandidates,
                        candidates = candidates,
                    )
                }
            }
            PhoneIdentityDiscoveryResult.NoCandidates ->
                PhoneIdentityState(status = PhoneIdentityStatus.ManualEntryRequired)
            is PhoneIdentityDiscoveryResult.Unavailable -> PhoneIdentityState(
                status = PhoneIdentityStatus.ManualEntryRequired,
                discoveryUnavailableReason = result.reason,
            )
        }
    }

    fun selectCandidate(number: E164PhoneNumber): Boolean {
        val candidate = mutableState.value.candidates.firstOrNull { it.number == number } ?: return false
        mutableState.value = PhoneIdentityState(
            status = PhoneIdentityStatus.Selected,
            candidates = mutableState.value.candidates,
            selected = candidate,
        )
        return true
    }

    fun requireManualEntry() {
        mutableState.value = PhoneIdentityState(status = PhoneIdentityStatus.ManualEntryRequired)
    }

    fun submitManualNumber(input: String): Boolean {
        val number = E164PhoneNumber.parse(input)
        if (number == null) {
            mutableState.value = PhoneIdentityState(
                status = PhoneIdentityStatus.ManualEntryRequired,
                failure = PhoneIdentityFailure.InvalidInternationalNumber,
            )
            return false
        }
        mutableState.value = PhoneIdentityState(
            status = PhoneIdentityStatus.Selected,
            selected = PhoneIdentityCandidate(number, PhoneIdentitySource.ManualEntry),
        )
        return true
    }

    suspend fun startVerification() {
        val selected = mutableState.value.selected ?: return
        applyVerification(verification.start(selected.number), selected)
    }

    suspend fun confirmVerification(challenge: PhoneIdentityVerificationChallenge, code: String) {
        applyVerification(verification.confirm(challenge, code), mutableState.value.selected)
    }

    suspend fun restore() {
        applyVerification(verification.restore(), null)
    }

    suspend fun clear() {
        verification.clear()
        mutableState.value = PhoneIdentityState(status = PhoneIdentityStatus.Unknown)
    }

    private fun applyVerification(
        result: PhoneIdentityVerificationResult,
        selected: PhoneIdentityCandidate?,
    ) {
        mutableState.value = when (result) {
            is PhoneIdentityVerificationResult.Verified -> PhoneIdentityState(
                status = PhoneIdentityStatus.Verified,
                selected = PhoneIdentityCandidate(
                    result.number,
                    PhoneIdentitySource.RestoredServerIdentity,
                    trust = PhoneIdentityTrust.Verified,
                ),
            )
            is PhoneIdentityVerificationResult.Pending -> PhoneIdentityState(
                status = PhoneIdentityStatus.VerificationPending,
                selected = selected,
            )
            PhoneIdentityVerificationResult.Rejected -> PhoneIdentityState(
                status = PhoneIdentityStatus.VerificationFailed,
                selected = selected,
                failure = PhoneIdentityFailure.VerificationRejected,
            )
            PhoneIdentityVerificationResult.Unavailable -> PhoneIdentityState(
                status = PhoneIdentityStatus.VerificationRequired,
                selected = selected,
                failure = PhoneIdentityFailure.VerificationUnavailable,
            )
            PhoneIdentityVerificationResult.NotFound -> PhoneIdentityState(status = PhoneIdentityStatus.Unknown)
        }
    }
}
