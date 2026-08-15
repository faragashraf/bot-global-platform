using System.Globalization;
using System.Security.Claims;
using BotGlobal.Communication.Application.MobileNotifications;
using BotGlobal.Communication.Contracts.MobileNotifications;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;

namespace BotGlobal.Communication.Endpoints;

public sealed record MobileNotificationMachineAuthorizationOptions(
    string ClientIdClaimType,
    Func<string, AuthorizationPolicy> CapabilityPolicy);

public static class MobileNotificationEndpoints
{
    public const string SendCapability = "notifications:send";

    public static IEndpointRouteBuilder MapMobileNotificationEndpoints(
        this IEndpointRouteBuilder endpoints,
        MobileNotificationMachineAuthorizationOptions authorization)
    {
        ArgumentNullException.ThrowIfNull(endpoints);
        ArgumentNullException.ThrowIfNull(authorization);

        endpoints.MapPost(
                "/api/mobile-notifications",
                async (
                    ClaimsPrincipal principal,
                    SendMobileNotificationRequest request,
                    IMobileNotificationService service,
                    CancellationToken cancellationToken) =>
                {
                    var rawClientId =
                        principal.FindFirstValue(
                            authorization.ClientIdClaimType);

                    if (!Guid.TryParse(
                            rawClientId,
                            out var platformClientId))
                    {
                        return Results.Unauthorized();
                    }

                    try
                    {
                        var response =
                            await service.SendAsync(
                                platformClientId,
                                request,
                                cancellationToken);

                        return Results.Ok(response);
                    }
                    catch (ArgumentException exception)
                    {
                        return Results.ValidationProblem(
                            new Dictionary<string, string[]>
                            {
                                ["request"] =
                                [
                                    exception.Message
                                ]
                            });
                    }
                })
            .RequireAuthorization(
                authorization.CapabilityPolicy(
                    SendCapability))
            .WithName("SendMobileNotification")
            .WithTags("Mobile Notifications");

        return endpoints;
    }
}
