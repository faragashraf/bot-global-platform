package com.enpo.connect.app.network

import com.botglobal.mobile.platform.networking.NetworkClientConfiguration
import com.botglobal.mobile.platform.networking.NetworkEnvironment
import com.botglobal.mobile.platform.networking.ProductEndpointConfig
import com.enpo.connect.app.state.EnpoReleaseIdentity

enum class EnpoPublicServiceRoute(internal val path: String) {
    PairingClaim("/api/mobile/pairing/claim"),
    DeviceUnpair("/api/mobile/devices/unpair"),
    PushRegistration("/api/mobile/devices/push-registration"),
}

class EnpoNetworkConfiguration private constructor(
    private val endpoints: ProductEndpointConfig,
) {
    val applicationId: String = EnpoReleaseIdentity.ApplicationId
    val environment: NetworkEnvironment = endpoints.environment
    val clientConfiguration = NetworkClientConfiguration(
        connectTimeoutMillis = 12_000,
        requestTimeoutMillis = 18_000,
        socketTimeoutMillis = 18_000,
    )

    fun endpoint(route: EnpoPublicServiceRoute): String = endpoints.endpoint(route.path)

    override fun toString(): String =
        "EnpoNetworkConfiguration(applicationId=$applicationId,environment=$environment,baseUrl=<redacted>)"

    companion object {
        fun from(
            publicBaseUrl: String,
            environment: NetworkEnvironment,
        ): EnpoNetworkConfiguration = EnpoNetworkConfiguration(
            ProductEndpointConfig.from(publicBaseUrl, environment),
        )
    }
}
