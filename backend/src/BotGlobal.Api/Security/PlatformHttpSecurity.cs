using Microsoft.AspNetCore.Antiforgery;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.SignalR;

namespace BotGlobal.Api.Security;

public static class PlatformHttpSecurity
{
    public const string TokenPath = "/api/security/antiforgery";
    public const string HeaderName = "X-XSRF-TOKEN";

    public static IServiceCollection AddPlatformHttpSecurity(
        this IServiceCollection services)
    {
        // Preserve the existing separate-origin browser login configuration.
        services.ConfigureApplicationCookie(options =>
        {
            options.Cookie.SameSite = SameSiteMode.None;
            options.Cookie.SecurePolicy = CookieSecurePolicy.Always;
        });

        services.AddAntiforgery();
        services.AddOptions<AntiforgeryOptions>().Configure<IHostEnvironment>((options, environment) =>
        {
            options.HeaderName = HeaderName;
            options.Cookie.Name = "__Host-BotGlobal.Antiforgery";
            options.Cookie.HttpOnly = true;
            options.Cookie.SecurePolicy = CookieSecurePolicy.Always;
            options.Cookie.SameSite = SameSiteMode.None;
            options.Cookie.Path = "/";
            if (environment.IsDevelopment())
            {
                // ng serve proxies /api to the existing local HTTP endpoint.
                options.Cookie.Name = "BotGlobal.Antiforgery.Development";
                options.Cookie.SecurePolicy = CookieSecurePolicy.SameAsRequest;
                options.Cookie.SameSite = SameSiteMode.Lax;
            }
        });
        return services;
    }

    // Run after authorization so its selected authentication ticket, rather
    // than the presence of an Authorization header/cookie, defines the boundary.
    public static IApplicationBuilder UsePlatformHttpSecurity(
        this IApplicationBuilder app) => app.Use(async (context, next) =>
    {
        var endpoint = context.GetEndpoint();
        var method = context.Request.Method;
        var mutates = HttpMethods.IsPost(method) || HttpMethods.IsPut(method)
            || HttpMethods.IsPatch(method) || HttpMethods.IsDelete(method);

        // SignalR owns negotiation and transport requests; its connection
        // protocol must not acquire a browser-only HTTP token requirement.
        if (endpoint is not null && mutates
            && endpoint.Metadata.GetMetadata<HubMetadata>() is null
            && await UsesApplicationCookieAsync(context))
        {
            try
            {
                await context.RequestServices.GetRequiredService<IAntiforgery>()
                    .ValidateRequestAsync(context);
            }
            catch (AntiforgeryValidationException)
            {
                context.Response.Headers.CacheControl = "no-store";
                await Results.Problem(
                    statusCode: StatusCodes.Status400BadRequest,
                    title: "Request verification failed.",
                    extensions: new Dictionary<string, object?>
                    {
                        ["code"] = "antiforgery_validation_failed"
                    }).ExecuteAsync(context);
                return;
            }
        }

        await next(context);
    });

    public static IEndpointRouteBuilder MapPlatformHttpSecurity(
        this IEndpointRouteBuilder endpoints)
    {
        endpoints.MapGet(TokenPath, (HttpContext context, IAntiforgery antiforgery) =>
        {
            var tokens = antiforgery.GetAndStoreTokens(context);
            context.Response.Headers.CacheControl = "no-store";
            // JSON supports a separately hosted frontend without exposing the
            // authentication or antiforgery cookie to JavaScript.
            return Results.Ok(new { requestToken = tokens.RequestToken });
        }).AllowAnonymous().WithName("GetBrowserAntiforgeryToken");
        return endpoints;
    }

    private static async Task<bool> UsesApplicationCookieAsync(HttpContext context)
    {
        var selected = context.Features.Get<IAuthenticateResultFeature>()
            ?.AuthenticateResult;
        if (selected?.Succeeded != true || selected.Ticket is null
            || !selected.Ticket.AuthenticationScheme.Split(';')
                .Contains(IdentityConstants.ApplicationScheme, StringComparer.Ordinal))
        {
            return false;
        }

        // A policy may list multiple alternative schemes even when only one
        // authenticated. Do not require CSRF proof for a bearer-only principal.
        return (await context.AuthenticateAsync(IdentityConstants.ApplicationScheme))
            .Succeeded;
    }
}
