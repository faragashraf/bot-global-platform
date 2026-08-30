using BotGlobal.Pairing.Application.PushRegistrations;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Notifications;
using System.Security.Claims;
using BotGlobal.Pairing.Application;
using BotGlobal.Pairing.Contracts;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.ModelBinding;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.AspNetCore.Routing;
using BotGlobal.Pairing.Security;

namespace BotGlobal.Pairing.Endpoints;

public static class PairingEndpoints
{
    public static IEndpointRouteBuilder MapPairingEndpoints(
        this IEndpointRouteBuilder endpoints,
        PairingMachineAuthorizationOptions machineAuthorization)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(
            machineAuthorization.PlatformClientIdClaimType);

        var machineGroup = endpoints
            .MapGroup("/api/pairing/challenges")
            .WithTags("Pairing");

        machineGroup
            .MapPost("/", CreateAsync)
            .RequireAuthorization(
                machineAuthorization.CapabilityPolicyFactory(
                    PairingCapabilities.Create))
            .RequireRateLimiting(PairingModule.MachineCreateRateLimitPolicy)
            .WithName("CreatePairingChallenge")
            .WithSummary("Create a short-lived mobile pairing challenge.");

        machineGroup
            .MapGet("/{challengeId:guid}", GetStatusAsync)
            .RequireAuthorization(
                machineAuthorization.CapabilityPolicyFactory(
                    PairingCapabilities.Status))
            .RequireRateLimiting(PairingModule.MachineStatusRateLimitPolicy)
            .WithName("GetPairingChallengeStatus")
            .WithSummary("Read the status of an owned mobile pairing challenge.");

        var mobileGroup = endpoints
            .MapGroup("/api/mobile/pairing")
            .WithTags("Mobile Pairing");

        mobileGroup
            .MapPost("/claim", ClaimAsync)
            .AllowAnonymous()
            .RequireRateLimiting(PairingModule.MobileClaimRateLimitPolicy)
            .WithName("ClaimPairingChallenge")
            .WithSummary("Complete a pairing challenge from a scanned QR token.");


        endpoints.MapPut(
                "/api/mobile/devices/push-registration",
                async (
                    RegisterMobilePushRequest request,
                    ClaimsPrincipal principal,
                    IMobilePushRegistrationService service,
                    CancellationToken cancellationToken) =>
                {
                    var rawDeviceId =
                        principal.FindFirstValue(
                            MobileDeviceAuthenticationDefaults.DeviceIdClaim);
                    var rawApplicationId =
                        principal.FindFirstValue(
                            MobileDeviceAuthenticationDefaults.PlatformClientIdClaim);

                    if (!Guid.TryParse(
                            rawDeviceId,
                            out var deviceId)
                        || !Guid.TryParse(
                            rawApplicationId,
                            out var applicationId))
                    {
                        return Results.Unauthorized();
                    }

                    try
                    {
                        var result =
                            await service.RegisterAsync(
                                new NotificationApplicationContext(applicationId),
                                deviceId,
                                request,
                                cancellationToken);

                        return Results.Ok(result);
                    }
                    catch (ArgumentException exception)
                    {
                        return Results.ValidationProblem(
                            new Dictionary<string, string[]>
                            {
                                ["request"] = [exception.Message]
                            });
                    }
                    catch (InvalidOperationException)
                    {
                        return Results.Unauthorized();
                    }
                })
            .RequireAuthorization(
                policy =>
                    policy
                        .AddAuthenticationSchemes(
                            MobileDeviceAuthenticationDefaults.Scheme)
                        .RequireAuthenticatedUser())
            .WithName("RegisterMobilePush")
            .WithTags("Mobile Pairing")
            .WithSummary(
                "Register or refresh the authenticated device push destination.");

        endpoints.MapPost(
                "/api/mobile/devices/unpair",
                async (
                    HttpContext httpContext,
                    IMobileDeviceLifecycleService deviceLifecycleService,
                    CancellationToken cancellationToken) =>
                {
                    if (!MobileDeviceAuthorization.TryReadCredential(
                            httpContext.Request.Headers.Authorization,
                            out var credential))
                    {
                        return Results.Unauthorized();
                    }

                    var outcome =
                        await deviceLifecycleService.UnpairAsync(
                            credential,
                            cancellationToken);

                    return outcome switch
                    {
                        UnpairMobileDeviceOutcome.Unpaired =>
                            Results.NoContent(),

                        UnpairMobileDeviceOutcome.InvalidCredential =>
                            Results.Unauthorized(),

                        _ =>
                            Results.Unauthorized()
                    };
                })
            .AllowAnonymous()
            .WithName("UnpairMobileDevice")
            .WithTags("Mobile Pairing");

        return endpoints;

        async Task<IResult> CreateAsync(
            [FromBody(EmptyBodyBehavior = EmptyBodyBehavior.Allow)]
            CreatePairingChallengeRequest? request,
            ClaimsPrincipal principal,
            IPairingChallengeService service,
            CancellationToken cancellationToken)
        {
            if (!TryGetPlatformClientId(
                    principal,
                    machineAuthorization.PlatformClientIdClaimType,
                    out var platformClientId))
            {
                return Results.Unauthorized();
            }

            if (request is null)
            {
                return Results.ValidationProblem(
                    new Dictionary<string, string[]>
                    {
                        ["request"] =
                        [
                            "Pairing challenge request is required."
                        ]
                    });
            }

            try
            {
                return Results.Ok(
                    await service.CreateAsync(
                        platformClientId,
                        request,
                        cancellationToken));
            }
            catch (ArgumentException exception)
            {
                return Results.ValidationProblem(
                    new Dictionary<string, string[]>
                    {
                        ["request"] = [exception.Message]
                    });
            }
        }

        async Task<IResult> GetStatusAsync(
            Guid challengeId,
            ClaimsPrincipal principal,
            IPairingChallengeService service,
            CancellationToken cancellationToken)
        {
            if (!TryGetPlatformClientId(
                    principal,
                    machineAuthorization.PlatformClientIdClaimType,
                    out var platformClientId))
            {
                return Results.Unauthorized();
            }

            var result =
                await service.GetStatusAsync(
                    platformClientId,
                    challengeId,
                    cancellationToken);

            return result is null
                ? Results.NotFound()
                : Results.Ok(result);
        }

        async Task<IResult> ClaimAsync(
            ClaimPairingChallengeRequest request,
            IPairingChallengeService service,
            CancellationToken cancellationToken)
        {
            try
            {
                var result =
                    await service.ClaimAsync(
                        request,
                        cancellationToken);

                return result.Outcome
                    == ClaimPairingChallengeOutcome.Completed
                    ? Results.Ok(result.Response)
                    : Results.BadRequest(
                        new
                        {
                            message =
                                "Pairing challenge is invalid, expired, or already used."
                        });
            }
            catch (ArgumentException exception)
            {
                return Results.ValidationProblem(
                    new Dictionary<string, string[]>
                    {
                        ["request"] = [exception.Message]
                    });
            }
        }
    }

    private static bool TryGetPlatformClientId(
        ClaimsPrincipal principal,
        string claimType,
        out Guid platformClientId)
    {
        var value = principal.FindFirstValue(claimType);
        return Guid.TryParse(value, out platformClientId);
    }
}
