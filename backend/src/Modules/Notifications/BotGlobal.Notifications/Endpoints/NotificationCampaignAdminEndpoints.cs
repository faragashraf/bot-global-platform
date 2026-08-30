using System.Security.Claims;
using BotGlobal.Notifications.Application;
using BotGlobal.Notifications.Domain;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using BotGlobal.Contracts.Notifications;

namespace BotGlobal.Notifications.Endpoints;

public sealed record CreateNotificationCampaignRequest(
    Guid PlatformClientId,
    string TitleAr,
    string TitleEn,
    string BodyAr,
    string BodyEn,
    string Type,
    string Priority,
    int? LifetimeDays,
    string? AudienceKind);

public static class NotificationCampaignAdminEndpoints
{
    public static IEndpointRouteBuilder MapNotificationCampaignAdminEndpoints(
        this IEndpointRouteBuilder endpoints)
    {
        var group = endpoints
            .MapGroup("/api/admin/notification-campaigns")
            .WithTags("Admin - Notification Campaigns")
            .RequireAuthorization("Administrator");

        group.MapGet(
                "/audience-preview/{platformClientId:guid}",
                PreviewAudienceAsync)
            .WithName("AdminPreviewNotificationCampaignAudience");

        group.MapPost("/", CreateAsync)
            .RequireRateLimiting(NotificationsModule.AdminCreateRateLimitPolicy)
            .WithName("AdminCreateNotificationCampaign");

        group.MapGet("/", ListAsync)
            .WithName("AdminListNotificationCampaigns");

        group.MapGet("/{campaignId:guid}", FindAsync)
            .WithName("AdminGetNotificationCampaign");

        return endpoints;
    }

    private static async Task<IResult> PreviewAudienceAsync(
        Guid platformClientId,
        INotificationCampaignService service,
        CancellationToken cancellationToken)
    {
        try
        {
            return Results.Ok(
                await service.PreviewAudienceAsync(
                    platformClientId,
                    cancellationToken));
        }
        catch (NotificationCampaignValidationException exception)
        {
            return Results.ValidationProblem(exception.Errors);
        }
    }

    private static async Task<IResult> CreateAsync(
        HttpRequest httpRequest,
        ClaimsPrincipal principal,
        CreateNotificationCampaignRequest request,
        INotificationCampaignService service,
        IAdministratorDescriptorReader administrators,
        CancellationToken cancellationToken)
    {
        var rawUserId = principal.FindFirstValue(ClaimTypes.NameIdentifier);
        if (!Guid.TryParse(rawUserId, out var userId))
        {
            return Results.Unauthorized();
        }

        var administrator = await administrators.FindAsync(
            userId,
            cancellationToken);

        if (administrator is null || !administrator.IsActive)
        {
            return Results.Unauthorized();
        }

        if (!httpRequest.Headers.TryGetValue(
                "Idempotency-Key",
                out var idempotencyValues)
            || idempotencyValues.Count != 1
            || string.IsNullOrWhiteSpace(idempotencyValues[0]))
        {
            return Results.ValidationProblem(
                new Dictionary<string, string[]>
                {
                    ["idempotencyKey"] = ["The Idempotency-Key header is required."]
                });
        }

        try
        {
            var response = await service.CreateAsync(
                new CreateNotificationCampaignCommand(
                    request.PlatformClientId,
                    request.TitleAr,
                    request.TitleEn,
                    request.BodyAr,
                    request.BodyEn,
                    request.Type,
                    request.Priority,
                    request.LifetimeDays,
                    request.AudienceKind
                        ?? nameof(NotificationAudienceKind.AllCurrentActiveDevices),
                    idempotencyValues[0]!,
                    userId,
                    administrator.DisplayName),
                cancellationToken);

            return Results.Accepted(
                $"/api/admin/notification-campaigns/{response.CampaignId}",
                response);
        }
        catch (NotificationCampaignValidationException exception)
        {
            return Results.ValidationProblem(exception.Errors);
        }
        catch (NotificationCampaignConflictException exception)
        {
            return Results.Conflict(new { message = exception.Message });
        }
    }

    private static async Task<IResult> ListAsync(
        Guid? platformClientId,
        string? status,
        DateTimeOffset? fromUtc,
        DateTimeOffset? toUtc,
        int page,
        int pageSize,
        INotificationCampaignService service,
        CancellationToken cancellationToken)
    {
        try
        {
            return Results.Ok(
                await service.ListAsync(
                    new NotificationCampaignListQuery(
                        platformClientId,
                        status,
                        fromUtc,
                        toUtc,
                        page,
                        pageSize),
                    cancellationToken));
        }
        catch (NotificationCampaignValidationException exception)
        {
            return Results.ValidationProblem(exception.Errors);
        }
    }

    private static async Task<IResult> FindAsync(
        Guid campaignId,
        INotificationCampaignService service,
        CancellationToken cancellationToken)
    {
        var campaign = await service.FindAsync(
            campaignId,
            cancellationToken);

        return campaign is null
            ? Results.NotFound()
            : Results.Ok(campaign);
    }
}
