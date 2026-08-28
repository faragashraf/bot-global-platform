using System.Security.Claims;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Games.Application.Sessions;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Extensions.Logging;

namespace BotGlobal.Games.Realtime;

[Authorize(
    AuthenticationSchemes = ApplicationIdentityDefaults.Scheme,
    Policy = "application:family-games")]
public sealed class GamesHub(
    IGameSessionService sessions,
    GameConnectionRegistry connections,
    ILogger<GamesHub> logger) : Hub
{
    public override Task OnConnectedAsync()
    {
        connections.Connected(Context.ConnectionId, RequireIdentity().MembershipId);
        return base.OnConnectedAsync();
    }

    public override async Task OnDisconnectedAsync(Exception? exception)
    {
        var disconnected = connections.Disconnected(Context.ConnectionId);
        if (disconnected.HasValue)
        {
            foreach (var sessionId in disconnected.Value.SessionIds)
            {
                await sessions.SetDisconnectedAsync(
                    disconnected.Value.MembershipId,
                    sessionId,
                    CancellationToken.None);
            }
        }

        if (exception is not null)
        {
            logger.LogWarning(exception, "Game realtime connection {ConnectionId} disconnected with an error", Context.ConnectionId);
        }

        await base.OnDisconnectedAsync(exception);
    }

    public async Task<GameSessionSnapshot> Rejoin(Guid sessionId)
    {
        var identity = RequireIdentity();
        var result = await sessions.RejoinAsync(identity, sessionId, Context.ConnectionAborted);
        var snapshot = RequireSuccess(result);
        await Groups.AddToGroupAsync(Context.ConnectionId, GroupName(sessionId), Context.ConnectionAborted);
        connections.Joined(Context.ConnectionId, sessionId);
        await Clients.Caller.SendAsync("GameStateUpdated", snapshot, Context.ConnectionAborted);
        return snapshot;
    }

    public async Task<GameSessionSnapshot> Ready(Guid sessionId) =>
        RequireSuccess(await sessions.ReadyAsync(RequireIdentity(), sessionId, Context.ConnectionAborted));

    public async Task<GameSessionSnapshot> Move(XoMoveRequest request) =>
        RequireSuccess(await sessions.MoveAsync(RequireIdentity(), request, Context.ConnectionAborted));

    public async Task<GameSessionSnapshot> RequestRematch(Guid sessionId) =>
        RequireSuccess(await sessions.RequestRematchAsync(RequireIdentity(), sessionId, Context.ConnectionAborted));

    public async Task<GameSessionSnapshot> AcceptRematch(Guid sessionId) =>
        RequireSuccess(await sessions.AcceptRematchAsync(RequireIdentity(), sessionId, Context.ConnectionAborted));

    public static string GroupName(Guid sessionId) => $"game:{sessionId:N}";

    private ApplicationIdentityDescriptor RequireIdentity()
    {
        var principal = Context.User;
        if (principal is null ||
            !Guid.TryParse(principal.FindFirstValue(ApplicationIdentityDefaults.MembershipIdClaim), out var membershipId))
        {
            throw new HubException("Authenticated application membership is unavailable.");
        }

        return new ApplicationIdentityDescriptor(
            membershipId,
            Guid.TryParse(principal.FindFirstValue(ClaimTypes.Sid), out var userId) ? userId : null,
            principal.FindFirstValue(ClaimTypes.NameIdentifier) ?? string.Empty,
            principal.FindFirstValue(ApplicationIdentityDefaults.ApplicationKeyClaim) ?? string.Empty,
            principal.Identity?.Name ?? string.Empty,
            string.Equals(
                principal.FindFirstValue(ApplicationIdentityDefaults.GuestClaim),
                "true",
                StringComparison.OrdinalIgnoreCase));
    }

    private static GameSessionSnapshot RequireSuccess(GameCommandResult<GameSessionSnapshot> result) =>
        result.Succeeded && result.Value is not null
            ? result.Value
            : throw new HubException($"{result.ErrorCode}:{result.ErrorMessage}");
}
