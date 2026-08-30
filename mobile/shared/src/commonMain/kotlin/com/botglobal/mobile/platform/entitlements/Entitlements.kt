package com.botglobal.mobile.platform.entitlements

sealed interface EntitlementKey {
    val value: String

    data class Game(override val value: String) : EntitlementKey
    data class GameMode(override val value: String) : EntitlementKey
    data class Ruleset(override val value: String) : EntitlementKey
    data class Capability(override val value: String) : EntitlementKey
}

data class EntitlementGrant(
    val key: String,
    val granted: Boolean,
    val expiresAtUtc: String? = null,
)

class EntitlementEngine(grants: Collection<EntitlementGrant>) {
    private val byKey = grants.associateBy(EntitlementGrant::key)

    fun isAllowed(requirement: EntitlementKey?): Boolean =
        requirement == null || byKey[requirement.value]?.granted == true
}

interface BillingProvider {
    suspend fun availableProducts(): List<BillingProduct>
    suspend fun purchase(productId: String): BillingResult
    suspend fun restore(): BillingResult
}

data class BillingProduct(val providerProductId: String, val entitlementKey: String)
sealed interface BillingResult {
    data object Completed : BillingResult
    data object Cancelled : BillingResult
    data class Failed(val safeMessage: String) : BillingResult
}
