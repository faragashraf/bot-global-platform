package com.botglobal.mobile.platform.invitations

data class GameInvitation(
    val invitationId: String,
    val sessionReference: String,
    val gameType: String,
    val invitationToken: String,
    val expiresAtUtc: String,
    val inviterDisplayName: String,
    val deepLink: String,
    val joinCode: String?,
)

data class ResolvedGameInvitation(
    val invitationId: String,
    val sessionReference: String,
)

sealed interface InvitationLinkResult {
    data class Valid(val token: String) : InvitationLinkResult
    data object Invalid : InvitationLinkResult
}

class InvitationLinkCodec(configuredBase: String) {
    private val linkBase = configuredBase.trim().trimEnd('/')

    init {
        require(linkBase.startsWith("familygames://") || linkBase.startsWith("https://")) {
            "Invitation links require a configured Family Games custom or HTTPS scheme."
        }
    }

    fun encode(invitationToken: String): String {
        require(TokenPattern.matches(invitationToken)) { "Invitation token is invalid." }
        return "$linkBase/$invitationToken"
    }

    fun parse(candidate: String): InvitationLinkResult {
        val normalized = candidate.trim()
        val prefix = "$linkBase/"
        if (!normalized.startsWith(prefix, ignoreCase = true)) return InvitationLinkResult.Invalid
        val token = normalized.substring(prefix.length)
        return if (TokenPattern.matches(token)) {
            InvitationLinkResult.Valid(token)
        } else {
            InvitationLinkResult.Invalid
        }
    }

    private companion object {
        val TokenPattern = Regex("^[A-Za-z0-9_-]{16,256}$")
    }
}

enum class InvitationMessageLanguage { Arabic, English }

data class InvitationShareContent(
    val title: String,
    val message: String,
)

object InvitationShareFormatter {
    fun format(
        language: InvitationMessageLanguage,
        gameName: String,
        deepLink: String,
        joinCode: String?,
    ): InvitationShareContent {
        val codeSuffix = joinCode?.takeIf { it.isNotBlank() }?.let {
            when (language) {
                InvitationMessageLanguage.Arabic -> "\nكود الانضمام: $it"
                InvitationMessageLanguage.English -> "\nJoin code: $it"
            }
        }.orEmpty()
        return when (language) {
            InvitationMessageLanguage.Arabic -> InvitationShareContent(
                title = "دعوة للعب $gameName",
                message = "تعال نلعب $gameName معًا! افتح الدعوة للانضمام:\n$deepLink$codeSuffix",
            )
            InvitationMessageLanguage.English -> InvitationShareContent(
                title = "Invitation to play $gameName",
                message = "Let’s play $gameName together! Open this invitation to join:\n$deepLink$codeSuffix",
            )
        }
    }
}

interface PlatformShareCapability {
    fun share(content: InvitationShareContent): Boolean
}

sealed interface QrScanResult {
    data class Recognized(val content: String) : QrScanResult {
        init {
            require(content.isNotBlank()) { "Recognized QR content cannot be blank." }
        }

        override fun toString(): String = "QrScanResult.Recognized(content=<redacted>)"
    }
    data object Cancelled : QrScanResult
    data object Unavailable : QrScanResult
}

interface QrScannerCapability {
    suspend fun scan(prompt: String): QrScanResult
}

object UnavailablePlatformShare : PlatformShareCapability {
    override fun share(content: InvitationShareContent) = false
}

object UnavailableQrScanner : QrScannerCapability {
    override suspend fun scan(prompt: String): QrScanResult = QrScanResult.Unavailable
}
