using System.Security.Claims;
using BotGlobal.Identity.Application;
using BotGlobal.Identity.Application.MobileVersionPolicies;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;

namespace BotGlobal.Identity.Endpoints;

internal static class MobileVersionPolicyAdminEndpoints
{
    public static IEndpointRouteBuilder MapMobileVersionPolicyAdminEndpoints(
        this IEndpointRouteBuilder endpoints)
    {
        var group = endpoints
            .MapGroup("/api/admin/mobile-version-policies")
            .RequireAuthorization(IdentityPolicies.Administrator)
            .WithTags("Admin - Mobile Version Policies");

        group.MapGet("/", ListAsync)
            .WithName("AdminListMobileVersionPolicies");

        group.MapPut("/{applicationKey}/{platform}", UpdateAsync)
            .WithName("AdminUpdateMobileVersionPolicy");

        return endpoints;
    }

    private static async Task<IResult> ListAsync(
        IMobileVersionPolicyAdminService service,
        CancellationToken cancellationToken) =>
        Results.Ok(await service.ListAsync(cancellationToken));

    private static async Task<IResult> UpdateAsync(
        string applicationKey,
        string platform,
        UpdateMobileVersionPolicyRequest request,
        ClaimsPrincipal principal,
        IMobileVersionPolicyAdminService service,
        CancellationToken cancellationToken)
    {
        try
        {
            return Results.Ok(await service.UpsertAsync(
                applicationKey,
                platform,
                request,
                Editor(principal),
                cancellationToken));
        }
        catch (KeyNotFoundException exception)
        {
            return Results.NotFound(new { message = exception.Message });
        }
        catch (ArgumentException exception)
        {
            return Results.ValidationProblem(
                new Dictionary<string, string[]>
                {
                    [exception.ParamName ?? "request"] = [exception.Message]
                });
        }
    }

    private static MobileVersionPolicyEditor Editor(ClaimsPrincipal principal) =>
        new(
            Guid.TryParse(principal.FindFirstValue(ClaimTypes.NameIdentifier), out var userId)
                ? userId
                : null,
            principal.Identity?.Name);
}
