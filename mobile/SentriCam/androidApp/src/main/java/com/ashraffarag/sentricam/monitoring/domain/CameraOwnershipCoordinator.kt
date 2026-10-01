package com.ashraffarag.sentricam.monitoring.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CameraOwner {
    NONE,
    CAMERA_ACTIVITY,
    MONITORING_SERVICE,
    LIVE_VIEW,
}

sealed interface CameraOwnershipResult {
    data object Acquired : CameraOwnershipResult
    data object AlreadyOwned : CameraOwnershipResult
    data class Rejected(val currentOwner: CameraOwner) : CameraOwnershipResult
}

/** Process-wide arbiter that prevents Activity and service CameraX bindings from overlapping. */
class CameraOwnershipCoordinator {
    private val mutableOwner = MutableStateFlow(CameraOwner.NONE)
    private var ownerToken: String? = null
    val owner: StateFlow<CameraOwner> = mutableOwner.asStateFlow()

    @Synchronized
    fun acquire(requester: CameraOwner, token: String = requester.name): CameraOwnershipResult {
        require(requester != CameraOwner.NONE)
        require(token.isNotBlank())
        return when (val current = mutableOwner.value) {
            CameraOwner.NONE -> {
                ownerToken = token
                mutableOwner.value = requester
                CameraOwnershipResult.Acquired
            }
            requester -> if (ownerToken == token) {
                CameraOwnershipResult.AlreadyOwned
            } else {
                CameraOwnershipResult.Rejected(current)
            }
            else -> CameraOwnershipResult.Rejected(current)
        }
    }

    @Synchronized
    fun release(requester: CameraOwner, token: String = requester.name): Boolean {
        if (mutableOwner.value != requester || ownerToken != token) return false
        ownerToken = null
        mutableOwner.value = CameraOwner.NONE
        return true
    }
}
