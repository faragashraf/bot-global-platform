package com.botglobal.mobile.platform.reviews

import com.botglobal.mobile.platform.preferences.PreferenceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class RatingInvitationPolicy(
    val minimumEvents: Int,
    val minimumEventSpanMillis: Long,
    val firstEventAgeMillis: Long,
    val laterDelayMillis: Long = 30L * 24 * 60 * 60 * 1_000,
    val storeOpenDelayMillis: Long = 180L * 24 * 60 * 60 * 1_000,
) {
    init {
        require(minimumEvents > 0)
        require(minimumEventSpanMillis >= 0)
        require(firstEventAgeMillis >= 0)
        require(laterDelayMillis > 0)
        require(storeOpenDelayMillis > 0)
    }
}

/** A product-owned invitation. Its rating action opens the Store listing, not Play's native review API. */
class RatingInvitationCoordinator(
    private val preferences: PreferenceStore,
    private val storageKey: String,
    private val policy: RatingInvitationPolicy,
    private val nowMillis: () -> Long,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableVisible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = mutableVisible.asStateFlow()

    fun recordMeaningfulEvent(eventId: String) {
        require(eventId.isNotBlank())
        val state = load()
        if (eventId !in state.eventIds) {
            save(state.copy(
                eventIds = (state.eventIds + eventId).takeLast(80),
                eventTimesMillis = (state.eventTimesMillis + nowMillis()).sorted().takeLast(80),
            ))
        }
        refresh()
    }

    fun refresh() {
        val now = nowMillis()
        val state = load()
        val firstEvent = state.eventTimesMillis.minOrNull()
        val lastEvent = state.eventTimesMillis.maxOrNull()
        mutableVisible.value = state.eventIds.size >= policy.minimumEvents &&
            firstEvent != null && lastEvent != null &&
            now - firstEvent >= policy.firstEventAgeMillis &&
            lastEvent - firstEvent >= policy.minimumEventSpanMillis &&
            (state.deferredAtMillis == null || now - state.deferredAtMillis >= policy.laterDelayMillis) &&
            (state.storeOpenedAtMillis == null || now - state.storeOpenedAtMillis >= policy.storeOpenDelayMillis)
    }

    fun later() {
        save(load().copy(deferredAtMillis = nowMillis()))
        mutableVisible.value = false
    }

    fun openedStore() {
        save(load().copy(storeOpenedAtMillis = nowMillis()))
        mutableVisible.value = false
    }

    fun suppressesNativePrompt(): Boolean {
        val state = load()
        val now = nowMillis()
        return mutableVisible.value ||
            (state.deferredAtMillis != null && now - state.deferredAtMillis < policy.laterDelayMillis) ||
            (state.storeOpenedAtMillis != null && now - state.storeOpenedAtMillis < policy.storeOpenDelayMillis)
    }

    private fun load(): RatingInvitationState =
        preferences.string(storageKey)?.let {
            runCatching { json.decodeFromString<RatingInvitationState>(it) }.getOrNull()
        } ?: RatingInvitationState()

    private fun save(state: RatingInvitationState) {
        preferences.putString(storageKey, json.encodeToString(state))
    }
}

@Serializable
private data class RatingInvitationState(
    val eventIds: List<String> = emptyList(),
    val eventTimesMillis: List<Long> = emptyList(),
    val deferredAtMillis: Long? = null,
    val storeOpenedAtMillis: Long? = null,
)
