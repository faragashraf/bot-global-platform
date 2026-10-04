using BotGlobal.Games.Domain.Xo;

namespace BotGlobal.Games.Application.Sessions;

public sealed record CreateGameSessionRequest(
    string RulesetKey,
    string? GameType = null,
    int? RoundCount = null,
    int? RoundSeconds = null,
    string? Difficulty = null,
    IReadOnlyList<string>? Categories = null);
public sealed record JoinGameSessionRequest(string JoinCode);
public sealed record ReadyGameSessionRequest(Guid SessionId);
public sealed record XoMoveRequest(
    Guid SessionId,
    string CommandId,
    int Row,
    int Column,
    long ExpectedVersion);
public sealed record RematchRequest(Guid SessionId);
public sealed record AutobusSubmitAnswersRequest(
    Guid SessionId,
    string CommandId,
    long ExpectedVersion,
    int MatchNumber,
    int Round,
    IReadOnlyDictionary<string, string> Answers);
public sealed record AutobusFinishRoundRequest(
    Guid SessionId,
    string CommandId,
    long ExpectedVersion);
public sealed record AutobusRevealRequest(
    Guid SessionId,
    string CommandId,
    long ExpectedVersion);
public sealed record AutobusVoteRequest(
    Guid SessionId,
    string CommandId,
    long ExpectedVersion,
    Guid AnswerOwnerMembershipId,
    string CategoryKey,
    bool Accept);

public sealed record GameRulesetSnapshot(
    string Key,
    int BoardSize,
    int WinLength,
    int PlayerCount,
    int? TurnTimeLimitSeconds,
    bool RematchEnabled,
    bool VoiceEnabled,
    string? RequiredEntitlement);

public sealed record GamePlayerSnapshot(
    Guid MembershipId,
    string DisplayName,
    int Seat,
    string Mark,
    bool IsReady,
    bool IsConnected);

public sealed record GameSessionSnapshot(
    Guid SessionId,
    string JoinCode,
    string GameType,
    string Status,
    int MatchNumber,
    GameRulesetSnapshot Ruleset,
    IReadOnlyList<GamePlayerSnapshot> Players,
    IReadOnlyList<string> Board,
    long Version,
    Guid? ActivePlayerMembershipId,
    Guid? WinnerMembershipId,
    string MatchStatus,
    Guid? RematchRequestedByMembershipId,
    DateTimeOffset LastActivityAtUtc,
    long Revision,
    AutobusSnapshot? Autobus = null);

public sealed record AutobusSnapshot(
    int SchemaVersion,
    string Difficulty,
    int RoundCount,
    int RoundSeconds,
    IReadOnlyList<AutobusCategorySnapshot> Categories,
    int CurrentRound,
    string? CurrentLetter,
    string Phase,
    DateTimeOffset? RoundStartedAtUtc,
    DateTimeOffset? RoundDeadlineAtUtc,
    DateTimeOffset? GraceEndsAtUtc,
    DateTimeOffset? VoteDeadlineAtUtc,
    string? RevealCategoryKey,
    IReadOnlyList<AutobusAnswerSnapshot> Answers,
    IReadOnlyList<AutobusScoreSnapshot> Scores,
    IReadOnlyList<AutobusVoteSnapshot> Votes,
    string? TieMessageCode);

public sealed record AutobusCategorySnapshot(
    string Key,
    string ArabicName,
    string EnglishName);

public sealed record AutobusAnswerSnapshot(
    Guid PlayerMembershipId,
    string CategoryKey,
    string DisplayAnswer,
    string Outcome,
    string ReasonCode,
    string FriendlyMessageCode,
    string? CanonicalValue,
    int Score,
    bool Scored,
    bool NeedsVote,
    bool Duplicate);

public sealed record AutobusScoreSnapshot(
    Guid PlayerMembershipId,
    int Score);

public sealed record AutobusVoteSnapshot(
    Guid AnswerOwnerMembershipId,
    string CategoryKey,
    Guid VoterMembershipId,
    bool Accept);

public sealed record GameCommandResult<T>(
    T? Value,
    string? ErrorCode,
    string? ErrorMessage,
    int StatusCode)
{
    public bool Succeeded => ErrorCode is null;

    public static GameCommandResult<T> Success(T value, int statusCode = 200) =>
        new(value, null, null, statusCode);

    public static GameCommandResult<T> Failure(string code, string message, int statusCode) =>
        new(default, code, message, statusCode);
}

public sealed record GameSemanticNotification(
    string Type,
    Guid RecipientMembershipId,
    Guid SessionId,
    string DeepLink,
    bool SuppressPushWhenActive);

public interface IGameNotificationPublisher
{
    Task PublishAsync(GameSemanticNotification notification, CancellationToken cancellationToken);
}

internal sealed class DeferredGameNotificationPublisher : IGameNotificationPublisher
{
    public Task PublishAsync(GameSemanticNotification notification, CancellationToken cancellationToken) =>
        Task.CompletedTask;
}

public interface IGameRealtimeNotifier
{
    Task SessionCreatedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken);
    Task PlayerJoinedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken);
    Task PlayerReadyAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken);
    Task GameStartedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken);
    Task StateUpdatedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken);
    Task MoveAcceptedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken);
    Task PlayerConnectionChangedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken);
    Task GameCompletedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken);
    Task RematchRequestedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken);
    Task RematchAcceptedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken);
}
