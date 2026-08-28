package com.botglobal.mobile.platform.update

enum class UpdateMode { None, Optional, Required }

data class AppVersionPolicy(
    val currentVersion: String,
    val latestVersion: String,
    val minimumSupportedVersion: String,
    val message: String? = null,
    val storeDestination: String? = null,
)

data class UpdateDecision(
    val mode: UpdateMode,
    val message: String?,
    val storeDestination: String?,
)

object UpdatePolicyEngine {
    fun decide(policy: AppVersionPolicy): UpdateDecision {
        val current = SemanticVersion.parse(policy.currentVersion)
        val minimum = SemanticVersion.parse(policy.minimumSupportedVersion)
        val latest = SemanticVersion.parse(policy.latestVersion)
        val mode = when {
            current < minimum -> UpdateMode.Required
            current < latest -> UpdateMode.Optional
            else -> UpdateMode.None
        }
        return UpdateDecision(mode, policy.message, policy.storeDestination)
    }
}

private data class SemanticVersion(val parts: List<Int>) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        val count = maxOf(parts.size, other.parts.size)
        for (index in 0 until count) {
            val compared = (parts.getOrNull(index) ?: 0).compareTo(other.parts.getOrNull(index) ?: 0)
            if (compared != 0) return compared
        }
        return 0
    }

    companion object {
        fun parse(value: String): SemanticVersion {
            val clean = value.trim().substringBefore('-')
            val parts = clean.split('.').map { part ->
                part.toIntOrNull() ?: throw IllegalArgumentException("Invalid semantic version: $value")
            }
            require(parts.isNotEmpty()) { "Version is required." }
            return SemanticVersion(parts)
        }
    }
}
