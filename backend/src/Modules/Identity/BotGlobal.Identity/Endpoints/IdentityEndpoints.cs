using System.Security.Claims;
using BotGlobal.Identity.Application;
using BotGlobal.Identity.Contracts;
using BotGlobal.Identity.Domain;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Routing;

namespace BotGlobal.Identity.Endpoints;

public static class IdentityEndpoints
{
    public static IEndpointRouteBuilder MapIdentityEndpoints(
        this IEndpointRouteBuilder endpoints)
    {
        var group =
            endpoints.MapGroup("/api/identity");

        group.MapPost("/login", LoginAsync)
            .AllowAnonymous();

        group.MapPost("/logout", LogoutAsync)
            .RequireAuthorization();

        group.MapGet("/me", CurrentUserAsync)
            .RequireAuthorization();

        group.MapGet(
                "/admin/ping",
                () => Results.Ok(
                    new { status = "ok" }))
            .RequireAuthorization(
                IdentityPolicies.Administrator);

        return endpoints;
    }

    private static async Task<IResult> LoginAsync(
        LoginRequest request,
        UserManager<ApplicationUser> userManager,
        SignInManager<ApplicationUser> signInManager)
    {
        if (string.IsNullOrWhiteSpace(
                request.UserNameOrEmail)
            || string.IsNullOrWhiteSpace(
                request.Password))
        {
            return Results.ValidationProblem(
                new Dictionary<string, string[]>
                {
                    ["credentials"] =
                    [
                        "Username/email and password are required."
                    ]
                });
        }

        var lookup =
            request.UserNameOrEmail.Trim();

        var user =
            lookup.Contains('@')
                ? await userManager.FindByEmailAsync(
                    lookup)
                : await userManager.FindByNameAsync(
                    lookup);

        if (user is null || !user.IsActive)
        {
            return InvalidCredentials();
        }

        var result =
            await signInManager.PasswordSignInAsync(
                user,
                request.Password,
                request.RememberMe,
                lockoutOnFailure: true);

        return result.Succeeded
            ? Results.NoContent()
            : InvalidCredentials();
    }

    private static async Task<IResult> LogoutAsync(
        SignInManager<ApplicationUser> signInManager)
    {
        await signInManager.SignOutAsync();

        return Results.NoContent();
    }

    private static async Task<IResult> CurrentUserAsync(
        ClaimsPrincipal principal,
        UserManager<ApplicationUser> userManager)
    {
        var user =
            await userManager.GetUserAsync(principal);

        if (user is null || !user.IsActive)
        {
            return Results.Unauthorized();
        }

        var roles =
            await userManager.GetRolesAsync(user);

        return Results.Ok(
            new CurrentUserResponse(
                user.Id,
                user.UserName ?? string.Empty,
                user.Email ?? string.Empty,
                user.DisplayName,
                roles.ToArray()));
    }

    private static IResult InvalidCredentials()
    {
        return Results.Problem(
            statusCode:
                StatusCodes.Status401Unauthorized,
            title:
                "Authentication failed",
            detail:
                "The supplied credentials are invalid.");
    }
}
