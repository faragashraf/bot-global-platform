using BotGlobal.Identity.Application.MobileVersionPolicies;
using Microsoft.AspNetCore.Mvc;

namespace BotGlobal.Api.MobileVersionPolicy;

internal static class MobileVersionPolicyEndpoint
{
    public static IEndpointRouteBuilder MapMobileVersionPolicyEndpoint(
        this IEndpointRouteBuilder endpoints)
    {
        endpoints.MapGet(
                "/api/mobile/apps/{appKey}/version-policy",
                async (
                    string appKey,
                    string platform,
                    string currentVersion,
                    [FromServices] IMobileVersionPolicyReader reader,
                    CancellationToken cancellationToken) =>
                    await reader.ReadAsync(appKey, platform, currentVersion, cancellationToken) is { } policy
                        ? Results.Ok(policy)
                        : Results.NotFound())
            .AllowAnonymous();

        return endpoints;
    }
}
