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
        val relativePath = path.trimStart('/')
        require(relativePath.isNotBlank() && relativePath.none(Char::isWhitespace) &&
            hasUnambiguousUrlPath(relativePath)) {
            "Endpoint paths must be unambiguous relative paths."
        }
        return "$baseUrl/$relativePath"
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
            require('\\' !in normalized && normalized.none(Char::isWhitespace)) {
                "A base URL cannot contain whitespace or backslashes."
            }
            val serverAndPath = normalized.substringAfter("://", missingDelimiterValue = "")
            val authority = serverAndPath.substringBefore('/')
            val pathPrefix = serverAndPath.substringAfter('/', missingDelimiterValue = "")
            require(
                hasValidAuthority(authority),
            ) {
                "A base URL must contain a valid server authority."
            }
            require(isSafePathPrefix(pathPrefix)) {
                "A base URL path prefix must be a safe relative path."
            }
            return ProductEndpointConfig(normalized, environment)
        }

        private fun hasValidAuthority(authority: String): Boolean {
            if (authority.isBlank() || '@' in authority || authority.any(Char::isWhitespace)) return false
            if (authority.startsWith('[')) {
                val closingBracket = authority.indexOf(']')
                if (closingBracket <= 1) return false
                val address = authority.substring(1, closingBracket)
                if (':' !in address || address.any { it !in "0123456789abcdefABCDEF:." }) return false
                val suffix = authority.substring(closingBracket + 1)
                return suffix.isEmpty() ||
                    suffix.startsWith(':') && suffix.length > 1 && suffix.drop(1).all(Char::isDigit)
            }
            if ('[' in authority || ']' in authority) return false
            val host = authority.substringBefore(':')
            val port = authority.substringAfter(':', missingDelimiterValue = "")
            return host.isNotBlank() &&
                (':' !in authority || port.isNotBlank() && port.all(Char::isDigit))
        }

        private fun isSafePathPrefix(value: String): Boolean {
            if (value.isEmpty()) return true
            if (value.startsWith('/') || value.endsWith('/')) return false
            if (!hasUnambiguousUrlPath(value)) return false
            return value.split('/').all { segment ->
                segment.isNotBlank() &&
                    '@' !in segment &&
                    segment.none(Char::isWhitespace)
            }
        }
    }
}
