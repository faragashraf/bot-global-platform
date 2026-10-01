package com.botglobal.lamma.app.data

import com.botglobal.mobile.platform.networking.NetworkEnvironment
import com.botglobal.mobile.platform.networking.ProductEndpointConfig

/**
 * The single server environment used by HTTP APIs, invitations, and realtime.
 * Route composition stays outside game and invitation domain logic.
 */
class FamilyGamesEnvironment private constructor(
    private val endpoints: ProductEndpointConfig,
) {
    val apiBaseUrl: String = endpoints.baseUrl
    val gamesHubUrl: String = endpoint("/hubs/games")

    fun endpoint(path: String): String = endpoints.endpoint(path)

    companion object {
        fun from(apiBaseUrl: String): FamilyGamesEnvironment {
            val normalized = apiBaseUrl.trim().trimEnd('/')
            val environment = if (normalized.startsWith("http://")) {
                NetworkEnvironment.Development
            } else {
                NetworkEnvironment.Production
            }
            return FamilyGamesEnvironment(ProductEndpointConfig.from(normalized, environment))
        }
    }
}
