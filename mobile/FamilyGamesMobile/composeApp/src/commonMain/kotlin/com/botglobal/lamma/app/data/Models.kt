package com.botglobal.lamma.app.data

import com.botglobal.mobile.platform.identity.ApplicationIdentity
import com.botglobal.mobile.platform.identity.IdentityKind
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.invitations.GameInvitation
import kotlinx.serialization.Serializable
import com.botglobal.mobile.platform.update.AppVersionPolicy

@Serializable
data class IdentityDto(
    val membershipId: String,
    val subjectId: String,
    val displayName: String,
    val isGuest: Boolean,
    val applicationKey: String,
) {
    fun toDomain(fallbackKind: IdentityKind = if (isGuest) IdentityKind.Guest else IdentityKind.Registered) =
        ApplicationIdentity(
            membershipId,
            subjectId,
            displayName,
            if (isGuest) IdentityKind.Guest else fallbackKind,
            applicationKey,
        )
}

@Serializable
data class MobileSessionDto(
    val accessToken: String,
    val accessExpiresAtUtc: String,
    val refreshToken: String,
    val refreshExpiresAtUtc: String,
    val identity: IdentityDto,
) {
    fun toDomain() = MobileSession(
        accessToken,
        accessExpiresAtUtc,
        refreshToken,
        refreshExpiresAtUtc,
        identity.toDomain(),
    )

    companion object {
        fun fromDomain(session: MobileSession) = MobileSessionDto(
            session.accessToken,
            session.accessExpiresAtUtc,
            session.refreshToken,
            session.refreshExpiresAtUtc,
            IdentityDto(
                session.identity.membershipId,
                session.identity.subjectId,
                session.identity.displayName,
                session.identity.kind == IdentityKind.Guest,
                session.identity.applicationKey,
            ),
        )
    }
}

@Serializable data class GuestRequest(val displayName: String)
@Serializable data class LoginRequest(val userNameOrEmail: String, val password: String)
@Serializable data class RegistrationRequest(
    val userName: String,
    val email: String,
    val displayName: String,
    val password: String,
)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class FederatedIdentityRequest(val provider: String, val idToken: String)
@Serializable data class ProfileUpdateRequest(val displayName: String)
@Serializable data class CreateSessionRequest(
    val rulesetKey: String,
    val gameType: String? = null,
    val roundCount: Int? = null,
    val roundSeconds: Int? = null,
    val difficulty: String? = null,
    val categories: List<String>? = null,
)
@Serializable
data class AppVersionPolicyDto(
    val currentVersion: String,
    val latestVersion: String,
    val minimumSupportedVersion: String,
    val message: String? = null,
    val storeDestination: String? = null,
) {
    fun toDomain() = AppVersionPolicy(
        currentVersion,
        latestVersion,
        minimumSupportedVersion,
        message,
        storeDestination,
    )
}
@Serializable data class JoinSessionRequest(val joinCode: String)
@Serializable data class ResolveInvitationRequest(val token: String)

@Serializable
data class GameInvitationDto(
    val invitationId: String,
    val sessionId: String,
    val gameType: String,
    val invitationToken: String,
    val expiresAtUtc: String,
    val inviterDisplayName: String,
    val deepLink: String,
    val joinCode: String? = null,
) {
    fun toDomain() = GameInvitation(
        invitationId = invitationId,
        sessionReference = sessionId,
        gameType = gameType,
        invitationToken = invitationToken,
        expiresAtUtc = expiresAtUtc,
        inviterDisplayName = inviterDisplayName,
        deepLink = deepLink,
        joinCode = joinCode,
    )
}

@Serializable
data class ResolvedGameInvitationDto(
    val invitationId: String,
    val session: GameSessionSnapshot,
)
@Serializable data class MoveRequest(
    val sessionId: String,
    val commandId: String,
    val row: Int,
    val column: Int,
    val expectedVersion: Long,
)
@Serializable data class AutobusSubmitAnswersRequest(
    val sessionId: String,
    val commandId: String,
    val expectedVersion: Long,
    val matchNumber: Int,
    val round: Int,
    val answers: Map<String, String>,
)
@Serializable data class AutobusFinishRoundRequest(
    val sessionId: String,
    val commandId: String,
    val expectedVersion: Long,
)
@Serializable data class AutobusRevealRequest(
    val sessionId: String,
    val commandId: String,
    val expectedVersion: Long,
)
@Serializable data class AutobusVoteRequest(
    val sessionId: String,
    val commandId: String,
    val expectedVersion: Long,
    val answerOwnerMembershipId: String,
    val categoryKey: String,
    val accept: Boolean,
)

@Serializable
data class RulesetSnapshot(
    val key: String,
    val boardSize: Int,
    val winLength: Int,
    val playerCount: Int,
    val turnTimeLimitSeconds: Int? = null,
    val rematchEnabled: Boolean,
    val voiceEnabled: Boolean,
    val requiredEntitlement: String? = null,
)

@Serializable
data class PlayerSnapshot(
    val membershipId: String,
    val displayName: String,
    val seat: Int,
    val mark: String,
    val isReady: Boolean,
    val isConnected: Boolean,
)

@Serializable
data class GameSessionSnapshot(
    val sessionId: String,
    val joinCode: String,
    val gameType: String,
    val status: String,
    val matchNumber: Int,
    val ruleset: RulesetSnapshot,
    val players: List<PlayerSnapshot>,
    val board: List<String>,
    val version: Long,
    val activePlayerMembershipId: String? = null,
    val winnerMembershipId: String? = null,
    val matchStatus: String,
    val rematchRequestedByMembershipId: String? = null,
    val lastActivityAtUtc: String,
    val revision: Long = 0,
    val autobus: AutobusSnapshot? = null,
)

@Serializable
data class AutobusSnapshot(
    val schemaVersion: Int,
    val difficulty: String,
    val roundCount: Int,
    val roundSeconds: Int,
    val categories: List<AutobusCategorySnapshot>,
    val currentRound: Int,
    val currentLetter: String? = null,
    val phase: String,
    val roundStartedAtUtc: String? = null,
    val roundDeadlineAtUtc: String? = null,
    val graceEndsAtUtc: String? = null,
    val voteDeadlineAtUtc: String? = null,
    val revealCategoryKey: String? = null,
    val answers: List<AutobusAnswerSnapshot> = emptyList(),
    val scores: List<AutobusScoreSnapshot> = emptyList(),
    val votes: List<AutobusVoteSnapshot> = emptyList(),
    val tieMessageCode: String? = null,
)

@Serializable
data class AutobusCategorySnapshot(
    val key: String,
    val arabicName: String,
    val englishName: String,
)

@Serializable
data class AutobusAnswerSnapshot(
    val playerMembershipId: String,
    val categoryKey: String,
    val displayAnswer: String,
    val outcome: String,
    val reasonCode: String,
    val friendlyMessageCode: String,
    val canonicalValue: String? = null,
    val score: Int,
    val scored: Boolean,
    val needsVote: Boolean,
    val duplicate: Boolean,
)

@Serializable
data class AutobusScoreSnapshot(
    val playerMembershipId: String,
    val score: Int,
)

@Serializable
data class AutobusVoteSnapshot(
    val answerOwnerMembershipId: String,
    val categoryKey: String,
    val voterMembershipId: String,
    val accept: Boolean,
)

class ApiException(
    val code: String,
    val status: Int,
    override val message: String,
) : Exception(message)
