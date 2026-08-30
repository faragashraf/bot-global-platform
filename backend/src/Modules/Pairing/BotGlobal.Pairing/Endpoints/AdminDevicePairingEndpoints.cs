using System.Security.Claims;
using BotGlobal.Contracts.Notifications;
using BotGlobal.Pairing.Application.AdminDevicePairings;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;

namespace BotGlobal.Pairing.Endpoints;

public sealed record AdminRevokeDeviceRequest(
    bool PurgeHistory);

public static class AdminDevicePairingEndpoints
{
    public static IEndpointRouteBuilder MapAdminDevicePairingEndpoints(
        this IEndpointRouteBuilder endpoints)
    {
        var group = endpoints
            .MapGroup("/api/admin/device-pairings")
            .WithTags("Admin - Device Pairings")
            .RequireAuthorization("Administrator");

        group.MapGet("/", ListAsync)
            .WithName("AdminListDevicePairings");

        group.MapGet("/{deviceId:guid}", FindAsync)
            .WithName("AdminGetDevicePairing");

        group.MapPost("/{deviceId:guid}/revoke", RevokeAsync)
            .WithName("AdminRevokeDevicePairing");

        return endpoints;
    }

    private static async Task<IResult> ListAsync(
        Guid? platformClientId,
        IAdminDevicePairingService service,
        CancellationToken cancellationToken)
    {
        try
        {
            return Results.Ok(
                await service.ListAsync(
                    Scope(platformClientId),
                    cancellationToken));
        }
        catch (AdminDeviceApplicationScopeException exception)
        {
            return InvalidApplication(exception);
        }
        catch (ArgumentException exception)
        {
            return InvalidApplication(exception.Message);
        }
    }

    private static async Task<IResult> FindAsync(
        Guid deviceId,
        Guid? platformClientId,
        IAdminDevicePairingService service,
        CancellationToken cancellationToken)
    {
        AdminDevicePairingDetail? detail;
        try
        {
            detail = await service.FindAsync(
                Scope(platformClientId),
                deviceId,
                cancellationToken);
        }
        catch (AdminDeviceApplicationScopeException exception)
        {
            return InvalidApplication(exception);
        }
        catch (ArgumentException exception)
        {
            return InvalidApplication(exception.Message);
        }

        return detail is null
            ? Results.NotFound()
            : Results.Ok(detail);
    }

    private static async Task<IResult> RevokeAsync(
        Guid deviceId,
        Guid? platformClientId,
        AdminRevokeDeviceRequest request,
        ClaimsPrincipal principal,
        IAdminDevicePairingService service,
        IAdministratorDescriptorReader administrators,
        CancellationToken cancellationToken)
    {
        var rawUserId = principal.FindFirstValue(
            ClaimTypes.NameIdentifier);

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

        try
        {
            var result = await service.RevokeAsync(
                new AdminRevokeDeviceCommand(
                    deviceId,
                    Scope(platformClientId),
                    request?.PurgeHistory ?? false,
                    userId,
                    administrator.DisplayName),
                cancellationToken);

            return Results.Ok(result);
        }
        catch (InvalidOperationException exception)
        {
            return Results.Problem(
                statusCode: 404,
                title: "Device not found",
                detail: exception.Message);
        }
        catch (AdminDeviceApplicationScopeException exception)
        {
            return InvalidApplication(exception);
        }
        catch (ArgumentException exception)
        {
            return InvalidApplication(exception.Message);
        }
    }

    private static ApplicationAdministrationScope Scope(
        Guid? platformClientId) =>
        platformClientId.HasValue
            ? ApplicationAdministrationScope.ForApplication(
                platformClientId.Value)
            : ApplicationAdministrationScope.PlatformGlobal;

    private static IResult InvalidApplication(
        AdminDeviceApplicationScopeException exception) =>
        InvalidApplication(exception.Message);

    private static IResult InvalidApplication(
        string message) =>
        Results.ValidationProblem(
            new Dictionary<string, string[]>
            {
                ["platformClientId"] = [message]
            });
}
