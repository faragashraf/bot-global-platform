package com.ashraffarag.sentricam.capability.presentation

import com.ashraffarag.sentricam.capability.domain.CapabilityAccess

enum class CapabilityAccessKind { AVAILABLE, LOCKED, COMING_SOON, UNSUPPORTED, UNAVAILABLE }

data class CapabilityAccessUi(val kind: CapabilityAccessKind, val isActionEnabled: Boolean)

object CapabilityAccessUiMapper {
    fun map(access: CapabilityAccess): CapabilityAccessUi = when (access) {
        CapabilityAccess.Available -> CapabilityAccessUi(CapabilityAccessKind.AVAILABLE, true)
        is CapabilityAccess.RequiresUpgrade -> CapabilityAccessUi(CapabilityAccessKind.LOCKED, false)
        CapabilityAccess.ComingSoon -> CapabilityAccessUi(CapabilityAccessKind.COMING_SOON, false)
        is CapabilityAccess.UnsupportedOnDevice -> CapabilityAccessUi(CapabilityAccessKind.UNSUPPORTED, false)
        is CapabilityAccess.Unavailable -> CapabilityAccessUi(CapabilityAccessKind.UNAVAILABLE, false)
    }
}
