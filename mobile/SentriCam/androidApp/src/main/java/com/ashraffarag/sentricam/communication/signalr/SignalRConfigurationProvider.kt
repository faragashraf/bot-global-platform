package com.ashraffarag.sentricam.communication.signalr

import com.ashraffarag.sentricam.device.registration.RegistrationCallResult
import com.ashraffarag.sentricam.device.registration.ServerConfiguration
import com.ashraffarag.sentricam.device.registration.ServerUrlPolicy

class SignalRConfigurationProvider(
    private val serverConfiguration: ServerConfiguration,
) {
    fun load(): Result<SignalRConfiguration> {
        return when (val normalized = ServerUrlPolicy(serverConfiguration.isDebug).normalize(
            serverConfiguration.baseUrl(),
        )) {
            is RegistrationCallResult.Failure -> Result.failure(
                SignalRTransportException(SignalRFailureCode.INVALID_CONFIGURATION),
            )
            is RegistrationCallResult.Success -> Result.success(
                SignalRConfiguration(normalized.value + HUB_PATH),
            )
        }
    }

    private companion object {
        const val HUB_PATH = "hubs/device"
    }
}
