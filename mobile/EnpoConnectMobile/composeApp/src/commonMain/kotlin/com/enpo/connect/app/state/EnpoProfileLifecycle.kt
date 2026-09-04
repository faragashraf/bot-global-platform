package com.enpo.connect.app.state

import com.botglobal.mobile.platform.profile.ProfileController

suspend fun synchronizeEnpoProfile(
    bootstrapState: EnpoBootstrapState,
    controller: ProfileController,
) {
    if (bootstrapState == EnpoBootstrapState.DeviceCredentialAvailable) {
        controller.refresh()
    } else {
        controller.invalidate()
    }
}
