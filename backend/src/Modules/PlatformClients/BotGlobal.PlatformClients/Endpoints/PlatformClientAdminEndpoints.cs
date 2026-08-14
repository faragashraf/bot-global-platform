using BotGlobal.PlatformClients.Application.Credentials;
using BotGlobal.PlatformClients.Application.Queries;
using BotGlobal.PlatformClients.Application.Provisioning;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;

namespace BotGlobal.PlatformClients.Endpoints;

public sealed record CreatePlatformClientRequest(
    string ClientKey,
    string DisplayName,
    IReadOnlyCollection<string>? Capabilities);

public static class PlatformClientAdminEndpoints
{
    public static IEndpointRouteBuilder MapPlatformClientAdminEndpoints(
        this IEndpointRouteBuilder endpoints)
    {
        var group =
            endpoints
                .MapGroup("/api/admin/platform-clients")
                .WithTags("Admin - Platform Clients")
                .RequireAuthorization(
                    policy =>
                        policy.RequireRole(
                            "Administrator"));

        group.MapGet(
                "/",
                async (
                    IPlatformClientQueryService queries,
                    CancellationToken cancellationToken) =>
                    Results.Ok(
                        await queries.ListAsync(
                            cancellationToken)))
            .WithName("AdminListPlatformClients")
            .WithSummary("List registered machine/service clients.");

        group.MapPost(
                "/{clientId:guid}/credentials/rotate",
                RotateCredentialAsync)
            .WithName("AdminRotatePlatformClientCredential");

        group.MapPost(
                "/{clientId:guid}/credentials/{credentialId:guid}/revoke",
                RevokeCredentialAsync)
            .WithName("AdminRevokePlatformClientCredential");

        group.MapPost(
                "/",
                CreateAsync)
            .WithName(
                "AdminCreatePlatformClient")
            .WithSummary(
                "Create a machine/service client and return its generated secret once.");

        return endpoints;
    }

    private static async Task<IResult> RotateCredentialAsync(
        Guid clientId,
        IPlatformClientCredentialLifecycleService lifecycle,
        CancellationToken cancellationToken)
    {
        try
        {
            return Results.Ok(await lifecycle.RotateAsync(clientId, cancellationToken));
        }
        catch (KeyNotFoundException exception)
        {
            return Results.NotFound(new { message = exception.Message });
        }
        catch (InvalidOperationException exception)
        {
            return Results.Conflict(new { message = exception.Message });
        }
    }

    private static async Task<IResult> RevokeCredentialAsync(
        Guid clientId,
        Guid credentialId,
        IPlatformClientCredentialLifecycleService lifecycle,
        CancellationToken cancellationToken)
    {
        try
        {
            await lifecycle.RevokeAsync(clientId, credentialId, cancellationToken);
            return Results.NoContent();
        }
        catch (KeyNotFoundException exception)
        {
            return Results.NotFound(new { message = exception.Message });
        }
    }

    private static async Task<IResult> CreateAsync(
        CreatePlatformClientRequest request,
        IPlatformClientProvisioningService provisioning,
        CancellationToken cancellationToken)
    {
        try
        {
            var result =
                await provisioning.CreateAsync(
                    new CreatePlatformClientCommand(
                        request.ClientKey,
                        request.DisplayName,
                        request.Capabilities
                        ?? Array.Empty<string>()),
                    cancellationToken);

            return Results.Created(
                $"/api/admin/platform-clients/{result.ClientId}",
                result);
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
        catch (InvalidOperationException exception)
        {
            return Results.Conflict(
                new
                {
                    message = exception.Message
                });
        }
    }
}
