package com.enpo.connect.app.state

import com.botglobal.mobile.platform.device.InstallationIdentity
import com.botglobal.mobile.platform.notifications.MobileDeviceCredentialAvailability
import com.botglobal.mobile.platform.notifications.MobileDeviceCredentialVault

sealed interface EnpoDeviceBootstrapResult {
    data object Unpaired : EnpoDeviceBootstrapResult
    data object DeviceCredentialAvailable : EnpoDeviceBootstrapResult
    data object CredentialUnreadable : EnpoDeviceBootstrapResult
}

fun interface EnpoDeviceInfrastructure {
    suspend fun inspect(): EnpoDeviceBootstrapResult
}

class PlatformEnpoDeviceInfrastructure(
    private val installationIdentity: InstallationIdentity,
    private val credentialVault: MobileDeviceCredentialVault,
) : EnpoDeviceInfrastructure {
    override suspend fun inspect(): EnpoDeviceBootstrapResult {
        installationIdentity.getOrCreate()
        return when (credentialVault.availability()) {
            MobileDeviceCredentialAvailability.Absent -> EnpoDeviceBootstrapResult.Unpaired
            MobileDeviceCredentialAvailability.Available -> EnpoDeviceBootstrapResult.DeviceCredentialAvailable
            MobileDeviceCredentialAvailability.Unreadable -> EnpoDeviceBootstrapResult.CredentialUnreadable
        }
    }
}

object EmptyEnpoDeviceInfrastructure : EnpoDeviceInfrastructure {
    override suspend fun inspect(): EnpoDeviceBootstrapResult = EnpoDeviceBootstrapResult.Unpaired
}
