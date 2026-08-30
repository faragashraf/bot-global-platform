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
        IAdminDevicePairingService service,
        CancellationToken cancellationToken)
    {
        return Results.Ok(
            await service.ListAsync(cancellationToken));
    }

    private static async Task<IResult> FindAsync(
        Guid deviceId,
        IAdminDevicePairingService service,
        CancellationToken cancellationToken)
    {
        var detail = await service.FindAsync(
            deviceId,
            cancellationToken);

        return detail is null
            ? Results.NotFound()
            : Results.Ok(detail);
    }

    private static async Task<IResult> RevokeAsync(
        Guid deviceId,
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
    }
}
