using BotGlobal.Communication.Application.Presence;
using BotGlobal.Contracts.Communication;
using BotGlobal.Contracts.Mobile;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;

namespace BotGlobal.Communication.Endpoints;

internal static class PresenceEndpoints
{
    internal static IEndpointRouteBuilder MapPresenceEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var group = endpoints.MapGroup("/api/mobile/presence")
            .RequireAuthorization(policy => policy
                .AddAuthenticationSchemes(ApplicationIdentityDefaults.Scheme)
                .RequireAuthenticatedUser())
            .WithTags("Mobile Presence");
        group.MapPost("/lease", BootstrapAsync);
        group.MapPost("/lease/renew", RenewAsync);
        group.MapPost("/lease/invalidate", InvalidateAsync);
        return endpoints;
    }

    private static async Task<IResult> BootstrapAsync(
        HttpContext context,
        IPresenceLeaseService leases,
        CancellationToken cancellationToken)
    {
        var credential = Credential(context);
        if (credential is null) return Results.Unauthorized();
        var grant = await leases.BootstrapAsync(credential, cancellationToken);
        if (grant is null) return Results.StatusCode(StatusCodes.Status503ServiceUnavailable);
        NoStore(context);
        return Results.Ok(grant);
    }

    private static async Task<IResult> RenewAsync(
        PresenceLeaseRequest request,
        HttpContext context,
        IPresenceLeaseService leases,
        CancellationToken cancellationToken)
    {
        var credential = Credential(context);
        if (credential is null) return Results.Unauthorized();
        try
        {
            var grant = await leases.RenewAsync(credential, request.LeaseId, cancellationToken);
            if (grant is null) return Results.Unauthorized();
            NoStore(context);
            return Results.Ok(grant);
        }
        catch (ArgumentException) { return Results.BadRequest(new { code = "presence_lease_invalid" }); }
    }

    private static async Task<IResult> InvalidateAsync(
        PresenceLeaseRequest request,
        HttpContext context,
        IPresenceLeaseService leases,
        CancellationToken cancellationToken)
    {
        var credential = Credential(context);
        if (credential is null) return Results.Unauthorized();
        try { await leases.InvalidateAsync(credential, request.LeaseId, cancellationToken); }
        catch (ArgumentException) { return Results.BadRequest(new { code = "presence_lease_invalid" }); }
        return Results.NoContent();
    }

    private static PresenceConnectionCredential? Credential(HttpContext context) =>
        context.Items.TryGetValue(PresenceConnectionCredential.HttpContextItemKey, out var value)
            ? value as PresenceConnectionCredential
            : null;

    private static void NoStore(HttpContext context) =>
        context.Response.Headers.CacheControl = "no-store";

    private sealed record PresenceLeaseRequest(string LeaseId);
}
