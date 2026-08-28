using System.Security.Claims;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Games.Application.Sessions;
using BotGlobal.Games.Application.Startup;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;

namespace BotGlobal.Games.Endpoints;

internal static class GameSessionEndpoints
{
    public static IEndpointRouteBuilder MapGameSessionEndpoints(this IEndpointRouteBuilder endpoints)
    {
        endpoints.MapGet(
                "/api/mobile/family-games/version-policy",
                (string platform, string currentVersion, ApplicationVersionPolicyReader reader) =>
                    Results.Ok(reader.Read(platform, currentVersion)))
            .AllowAnonymous();

        var group = endpoints.MapGroup("/api/games/sessions")
            .RequireAuthorization(ApplicationIdentityPolicies.For(BotGlobalApplications.FamilyGames));

        group.MapPost("/", async (
            CreateGameSessionRequest request,
            ClaimsPrincipal principal,
            IGameSessionService service,
            CancellationToken cancellationToken) =>
            ToResult(await service.CreateAsync(ToIdentity(principal), request, cancellationToken)));

        group.MapPost("/join", async (
            JoinGameSessionRequest request,
            ClaimsPrincipal principal,
            IGameSessionService service,
            CancellationToken cancellationToken) =>
            ToResult(await service.JoinAsync(ToIdentity(principal), request, cancellationToken)));

        group.MapGet("/active", async (
            ClaimsPrincipal principal,
            IGameSessionService service,
            CancellationToken cancellationToken) =>
            ToResult(await service.GetActiveAsync(ToIdentity(principal), cancellationToken)));

        group.MapGet("/{sessionId:guid}", async (
            Guid sessionId,
            ClaimsPrincipal principal,
            IGameSessionService service,
            CancellationToken cancellationToken) =>
            ToResult(await service.GetAsync(ToIdentity(principal), sessionId, cancellationToken)));

        group.MapPost("/{sessionId:guid}/ready", async (
            Guid sessionId,
            ClaimsPrincipal principal,
            IGameSessionService service,
            CancellationToken cancellationToken) =>
            ToResult(await service.ReadyAsync(ToIdentity(principal), sessionId, cancellationToken)));

        group.MapPost("/{sessionId:guid}/rejoin", async (
            Guid sessionId,
            ClaimsPrincipal principal,
            IGameSessionService service,
            CancellationToken cancellationToken) =>
            ToResult(await service.RejoinAsync(ToIdentity(principal), sessionId, cancellationToken)));

        group.MapPost("/{sessionId:guid}/moves", async (
            Guid sessionId,
            XoMoveRequest request,
            ClaimsPrincipal principal,
            IGameSessionService service,
            CancellationToken cancellationToken) =>
        {
            if (request.SessionId != sessionId)
            {
                return Results.ValidationProblem(
                    new Dictionary<string, string[]> { ["sessionId"] = ["Route and command session ids must match."] });
            }

            return ToResult(await service.MoveAsync(ToIdentity(principal), request, cancellationToken));
        });

        group.MapPost("/{sessionId:guid}/rematch/request", async (
            Guid sessionId,
            ClaimsPrincipal principal,
            IGameSessionService service,
            CancellationToken cancellationToken) =>
            ToResult(await service.RequestRematchAsync(ToIdentity(principal), sessionId, cancellationToken)));

        group.MapPost("/{sessionId:guid}/rematch/accept", async (
            Guid sessionId,
            ClaimsPrincipal principal,
            IGameSessionService service,
            CancellationToken cancellationToken) =>
            ToResult(await service.AcceptRematchAsync(ToIdentity(principal), sessionId, cancellationToken)));

        return endpoints;
    }

    private static IResult ToResult(GameCommandResult<GameSessionSnapshot> result) =>
        result.Succeeded
            ? Results.Json(result.Value, statusCode: result.StatusCode)
            : Results.Problem(
                statusCode: result.StatusCode,
                title: result.ErrorCode,
                detail: result.ErrorMessage,
                extensions: new Dictionary<string, object?> { ["code"] = result.ErrorCode });

    private static ApplicationIdentityDescriptor ToIdentity(ClaimsPrincipal principal)
    {
        if (!Guid.TryParse(
            principal.FindFirstValue(ApplicationIdentityDefaults.MembershipIdClaim),
            out var membershipId))
        {
            throw new InvalidOperationException("Authenticated application membership is unavailable.");
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
}
