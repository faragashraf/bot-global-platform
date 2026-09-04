using System.Security.Claims;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Pairing.Application.Profiles;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;

namespace BotGlobal.Pairing.Endpoints;

public static class MobileProfileEndpoints
{
    public static IEndpointRouteBuilder MapMobileProfileEndpoints(
        this IEndpointRouteBuilder endpoints,
        PairingMachineAuthorizationOptions machineAuthorization)
    {
        endpoints.MapPut(
                "/api/mobile-profile-snapshots",
                async (
                    PublishMobileProfileSnapshotRequest request,
                    ClaimsPrincipal principal,
                    IMobileProfileSnapshotService service,
                    CancellationToken cancellationToken) =>
                {
                    if (!TryGetPlatformClientId(
                            principal,
                            machineAuthorization.PlatformClientIdClaimType,
                            out var platformClientId))
                    {
                        return Results.Unauthorized();
                    }

                    try
                    {
                        var result = await service.PublishAsync(
                            platformClientId,
                            request,
                            cancellationToken);

                        return result.Outcome switch
                        {
                            MobileProfilePublishOutcome.SubjectNotPaired =>
                                Results.NotFound(
                                    new { code = "profile_subject_not_paired" }),
                            MobileProfilePublishOutcome.VersionConflict =>
                                Results.Conflict(
                                    new
                                    {
                                        code = "profile_version_conflict",
                                        currentVersion = result.CurrentVersion
                                    }),
                            _ => Results.Ok(
                                new
                                {
                                    outcome = PublishOutcomeName(result.Outcome),
                                    currentVersion = result.CurrentVersion
                                })
                        };
                    }
                    catch (ArgumentException exception)
                    {
                        return Results.ValidationProblem(
                            new Dictionary<string, string[]>
                            {
                                ["request"] = [exception.Message]
                            });
                    }
                    catch (Microsoft.EntityFrameworkCore.DbUpdateException)
                    {
                        return Results.Conflict(
                            new { code = "profile_publish_conflict" });
                    }
                })
            .RequireAuthorization(
                machineAuthorization.CapabilityPolicyFactory(
                    PairingCapabilities.PublishProfile))
            .RequireRateLimiting(
                PairingModule.MachineProfilePublishRateLimitPolicy)
            .WithName("PublishMobileProfileSnapshot")
            .WithTags("Mobile Profiles")
            .WithSummary(
                "Publish an application-scoped mobile profile projection for an already paired subject.");

        endpoints.MapGet(
                "/api/mobile/profile",
                async (
                    ClaimsPrincipal principal,
                    IMobileProfileSnapshotService service,
                    CancellationToken cancellationToken) =>
                {
                    var rawApplicationId = principal.FindFirstValue(
                        MobileDeviceAuthenticationDefaults.PlatformClientIdClaim);
                    var externalSubjectId = principal.FindFirstValue(
                        MobileDeviceAuthenticationDefaults.ExternalSubjectIdClaim);

                    if (!Guid.TryParse(rawApplicationId, out var applicationId)
                        || string.IsNullOrWhiteSpace(externalSubjectId))
                    {
                        return Results.Unauthorized();
                    }

                    var snapshot = await service.ReadAsync(
                        applicationId,
                        externalSubjectId,
                        cancellationToken);

                    return snapshot is null
                        ? Results.NotFound(
                            new { code = "profile_not_available_yet" })
                        : Results.Ok(snapshot);
                })
            .RequireAuthorization(
                policy =>
                    policy
                        .AddAuthenticationSchemes(
                            MobileDeviceAuthenticationDefaults.Scheme)
                        .RequireAuthenticatedUser())
            .RequireRateLimiting(PairingModule.MobileProfileReadRateLimitPolicy)
            .WithName("GetMyMobileProfile")
            .WithTags("Mobile Profiles")
            .WithSummary(
                "Read the authenticated device subject's stored mobile profile projection.");

        return endpoints;
    }

    private static bool TryGetPlatformClientId(
        ClaimsPrincipal principal,
        string claimType,
        out Guid platformClientId)
    {
        var value = principal.FindFirstValue(claimType);
        return Guid.TryParse(value, out platformClientId);
    }

    private static string PublishOutcomeName(
        MobileProfilePublishOutcome outcome) => outcome switch
        {
            MobileProfilePublishOutcome.Created => "created",
            MobileProfilePublishOutcome.Updated => "updated",
            MobileProfilePublishOutcome.Unchanged => "unchanged",
            MobileProfilePublishOutcome.StaleIgnored => "stale_ignored",
            _ => throw new ArgumentOutOfRangeException(nameof(outcome))
        };
}
