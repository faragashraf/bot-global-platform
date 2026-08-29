using BotGlobal.Games.Application.Sessions;

namespace BotGlobal.Games.Application.Invitations;

public sealed record ResolveGameInvitationRequest(string Token);

public sealed record GameInvitationSnapshot(
    Guid InvitationId,
    Guid SessionId,
    string GameType,
    string InvitationToken,
    DateTimeOffset ExpiresAtUtc,
    string InviterDisplayName,
    string DeepLink,
    string? JoinCode);

public sealed record ResolvedGameInvitation(
    Guid InvitationId,
    GameSessionSnapshot Session);

public sealed class GameInvitationOptions
{
    public const string SectionName = "FamilyGames:Invitations";

    public int LifetimeMinutes { get; set; } = 10;
    public string DeepLinkBase { get; set; } = "familygames://invite";
}

public interface IGameInvitationService
{
    Task<GameCommandResult<GameInvitationSnapshot>> CreateAsync(
        BotGlobal.Contracts.Mobile.ApplicationIdentityDescriptor identity,
        Guid sessionId,
        CancellationToken cancellationToken);

    Task<GameCommandResult<ResolvedGameInvitation>> ResolveAsync(
        BotGlobal.Contracts.Mobile.ApplicationIdentityDescriptor identity,
        ResolveGameInvitationRequest request,
        CancellationToken cancellationToken);
}
