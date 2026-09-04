package com.botglobal.mobile.platform.profile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ProfileSnapshot(
    val displayName: String,
    val jobTitle: String?,
    val organizationUnit: String?,
    val version: Long,
    val updatedAtUtc: String,
) {
    init {
        require(displayName.isNotBlank()) { "Profile display name is required." }
        require(version > 0) { "Profile version must be positive." }
        require(updatedAtUtc.isNotBlank()) { "Profile update time is required." }
    }

    override fun toString(): String =
        "ProfileSnapshot(version=$version,content=<redacted>)"
}

sealed interface ProfileFetchResult {
    data class Available(val snapshot: ProfileSnapshot) : ProfileFetchResult
    data object NotAvailableYet : ProfileFetchResult
    data object AuthenticationRequired : ProfileFetchResult
    data object Failed : ProfileFetchResult
}

fun interface ProfileRepository {
    suspend fun fetchMyProfile(): ProfileFetchResult
}

sealed interface ProfileLoadState {
    data object NotLoaded : ProfileLoadState
    data object Loading : ProfileLoadState
    data class Ready(val snapshot: ProfileSnapshot) : ProfileLoadState
    data object NotAvailableYet : ProfileLoadState
    data object AuthenticationRequired : ProfileLoadState
    data object Error : ProfileLoadState
}

class ProfileController(
    private val repository: ProfileRepository,
) {
    private val refreshLock = Mutex()
    private val mutableState = MutableStateFlow<ProfileLoadState>(ProfileLoadState.NotLoaded)
    val state: StateFlow<ProfileLoadState> = mutableState.asStateFlow()

    suspend fun refresh() {
        refreshLock.withLock {
            mutableState.value = ProfileLoadState.Loading
            val result = try {
                repository.fetchMyProfile()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ProfileFetchResult.Failed
            }
            mutableState.value = when (result) {
                is ProfileFetchResult.Available -> ProfileLoadState.Ready(result.snapshot)
                ProfileFetchResult.NotAvailableYet -> ProfileLoadState.NotAvailableYet
                ProfileFetchResult.AuthenticationRequired -> ProfileLoadState.AuthenticationRequired
                ProfileFetchResult.Failed -> ProfileLoadState.Error
            }
        }
    }

    suspend fun invalidate() {
        refreshLock.withLock {
            mutableState.value = ProfileLoadState.NotLoaded
        }
    }
}

object UnavailableProfileRepository : ProfileRepository {
    override suspend fun fetchMyProfile(): ProfileFetchResult = ProfileFetchResult.Failed
}
