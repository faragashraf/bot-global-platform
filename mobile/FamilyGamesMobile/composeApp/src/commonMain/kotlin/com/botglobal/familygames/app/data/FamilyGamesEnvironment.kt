package com.botglobal.familygames.app.data

/**
 * The single server environment used by HTTP APIs, invitations, and realtime.
 * Route composition stays outside game and invitation domain logic.
 */
class FamilyGamesEnvironment private constructor(
    val apiBaseUrl: String,
) {
    val gamesHubUrl: String = endpoint("/hubs/games")

    fun endpoint(path: String): String = "$apiBaseUrl/${path.trimStart('/')}"

    companion object {
        fun from(apiBaseUrl: String): FamilyGamesEnvironment {
            val normalized = apiBaseUrl.trim().trimEnd('/')
            require(normalized.startsWith("https://") || normalized.startsWith("http://")) {
                "API base URL must use HTTP or HTTPS."
            }
            require('?' !in normalized && '#' !in normalized) {
                "API base URL must not include a query or fragment."
            }
            val authority = normalized.substringAfter("://")
            require(
                authority.isNotBlank() &&
                    !authority.startsWith('/') &&
                    '/' !in authority &&
                    '@' !in authority &&
                    authority.none(Char::isWhitespace),
            ) {
                "API base URL must contain only a server authority, without endpoint paths."
            }
            return FamilyGamesEnvironment(normalized)
        }
    }
}
