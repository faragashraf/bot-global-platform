using System.Security.Claims;
using BotGlobal.PlatformClients.Authentication;
using BotGlobal.PlatformClients.Authorization;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;

namespace BotGlobal.PlatformClients.Endpoints;

public static class PlatformClientProbeEndpoints
{
    public const string ProbeCapability = "platform-clients:probe";

    public static IEndpointRouteBuilder MapPlatformClientProbeEndpoints(
        this IEndpointRouteBuilder endpoints)
    {
        var group = endpoints
            .MapGroup("/api/platform-clients/probe")
            .WithTags("Platform Clients");

        group.MapGet(
                "/whoami",
                (ClaimsPrincipal principal) =>
                {
                    var clientId = principal.FindFirstValue(
                        PlatformClientAuthenticationDefaults.ClientIdClaim);

                    var clientKey = principal.FindFirstValue(
                        PlatformClientAuthenticationDefaults.ClientKeyClaim);

                    var capabilities = principal
                        .FindAll(
                            PlatformClientAuthenticationDefaults.CapabilityClaim)
                        .Select(claim => claim.Value)
                        .Order(StringComparer.Ordinal)
                        .ToArray();

                    return Results.Ok(
                        new
                        {
                            clientId,
                            clientKey,
                            capabilities
                        });
                })
            .RequireAuthorization(
                PlatformClientPolicies.Capability(
                    ProbeCapability));

        group.MapGet(
                "/capability",
                () => Results.Ok(
                    new
                    {
                        capability = ProbeCapability,
                        allowed = true
                    }))
            .RequireAuthorization(
                PlatformClientPolicies.Capability(
                    ProbeCapability));

        return endpoints;
    }
}
