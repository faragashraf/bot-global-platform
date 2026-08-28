using System.Security.Claims;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Identity.Application;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;

namespace BotGlobal.Identity.Endpoints;

internal static class MobileIdentityEndpoints
{
    public static IEndpointRouteBuilder MapFamilyGamesMobileIdentityEndpoints(
        this IEndpointRouteBuilder endpoints)
    {
        const string applicationKey = BotGlobalApplications.FamilyGames;
        var group = endpoints.MapGroup("/api/mobile/family-games/identity");

        group.MapPost("/guest", async (
            MobileGuestRequest request,
            IMobileIdentityService service,
            CancellationToken cancellationToken) =>
            ToResult(await service.ContinueAsGuestAsync(applicationKey, request, cancellationToken)))
            .AllowAnonymous();

        group.MapPost("/register", async (
            MobileRegistrationRequest request,
            IMobileIdentityService service,
            CancellationToken cancellationToken) =>
            ToResult(await service.RegisterAsync(applicationKey, request, cancellationToken)))
            .AllowAnonymous();

        group.MapPost("/login", async (
            MobileLoginRequest request,
            IMobileIdentityService service,
            CancellationToken cancellationToken) =>
            ToResult(await service.LoginAsync(applicationKey, request, cancellationToken)))
            .AllowAnonymous();

        group.MapPost("/refresh", async (
            MobileRefreshRequest request,
            IMobileIdentityService service,
            CancellationToken cancellationToken) =>
        {
            var session = await service.RefreshAsync(applicationKey, request, cancellationToken);
            return session is null ? Results.Unauthorized() : Results.Ok(session);
        }).AllowAnonymous();

        group.MapGet("/me", (ClaimsPrincipal principal) =>
        {
            var membershipId = RequireMembershipId(principal);
            return Results.Ok(new MobileIdentityResponse(
                membershipId,
                principal.FindFirstValue(ClaimTypes.NameIdentifier) ?? string.Empty,
                principal.Identity?.Name ?? string.Empty,
                string.Equals(
                    principal.FindFirstValue(ApplicationIdentityDefaults.GuestClaim),
                    "true",
                    StringComparison.OrdinalIgnoreCase),
                applicationKey));
        }).RequireAuthorization(ApplicationIdentityPolicies.For(applicationKey));

        group.MapPost("/upgrade", async (
            MobileRegistrationRequest request,
            ClaimsPrincipal principal,
            IMobileIdentityService service,
            CancellationToken cancellationToken) =>
            ToResult(await service.UpgradeGuestAsync(
                RequireMembershipId(principal), request, cancellationToken)))
            .RequireAuthorization(ApplicationIdentityPolicies.For(applicationKey));

        group.MapPost("/logout", async (
            HttpRequest request,
            IMobileApplicationTokenService tokens,
            CancellationToken cancellationToken) =>
        {
            const string prefix = "Bearer ";
            var authorization = request.Headers.Authorization.ToString();
            if (authorization.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
            {
                await tokens.RevokeAccessTokenAsync(authorization[prefix.Length..].Trim(), cancellationToken);
            }

            return Results.NoContent();
        }).RequireAuthorization(ApplicationIdentityPolicies.For(applicationKey));

        return endpoints;
    }

    private static IResult ToResult(MobileIdentityResult result) =>
        result.Succeeded
            ? Results.Ok(result.Session)
            : Results.ValidationProblem(result.Errors);

    private static Guid RequireMembershipId(ClaimsPrincipal principal) =>
        Guid.TryParse(
            principal.FindFirstValue(ApplicationIdentityDefaults.MembershipIdClaim),
            out var membershipId)
                ? membershipId
                : throw new InvalidOperationException("Authenticated application membership is unavailable.");
}
