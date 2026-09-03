package com.botglobal.mobile.platform.networking

enum class NetworkEnvironment {
    Development,
    Production,
}

class ProductEndpointConfig private constructor(
    val baseUrl: String,
    val environment: NetworkEnvironment,
) {
    fun endpoint(path: String): String {
        require(path.isNotBlank()) { "An endpoint path is required." }
        require('?' !in path && '#' !in path && '@' !in path) {
            "Endpoint paths cannot contain authority, query, or fragment data."
        }
        return "$baseUrl/${path.trimStart('/')}"
    }

    override fun toString(): String =
        "ProductEndpointConfig(environment=$environment,baseUrl=<redacted>)"

    companion object {
        fun from(
            baseUrl: String,
            environment: NetworkEnvironment,
        ): ProductEndpointConfig {
            val normalized = baseUrl.trim().trimEnd('/')
            val scheme = normalized.substringBefore("://", missingDelimiterValue = "")
            require(scheme == "https" || environment == NetworkEnvironment.Development && scheme == "http") {
                "Production endpoints require HTTPS; development endpoints require HTTP or HTTPS."
            }
            require('?' !in normalized && '#' !in normalized) {
                "A base URL cannot contain a query or fragment."
            }
            val authority = normalized.substringAfter("://", missingDelimiterValue = "")
            require(
                authority.isNotBlank() &&
                    !authority.startsWith('/') &&
                    '/' !in authority &&
                    '@' !in authority &&
                    authority.none(Char::isWhitespace),
            ) {
                "A base URL must contain only a server authority."
            }
            return ProductEndpointConfig(normalized, environment)
        }
    }
}
