using System.Security.Claims;
using System.Threading.RateLimiting;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Calling;
using BotGlobal.Contracts.Notifications;
using BotGlobal.Identity.Application;
using BotGlobal.Identity.Domain;
using BotGlobal.Identity.Endpoints;
using BotGlobal.Identity.Infrastructure;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Routing;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace BotGlobal.Identity;

public static class IdentityModule
{
    public const string MobileAccountDeletionRateLimitPolicy = "nqrb-mobile-account-deletion";
    public const string PublicNqrbAccountDeletionRateLimitPolicy = "nqrb-public-account-deletion";

    public static IServiceCollection AddIdentityModule(
        this IServiceCollection services,
        IConfiguration configuration)
    {
        var connectionString =
            configuration.GetConnectionString("Identity")
            ?? throw new InvalidOperationException(
                "Connection string 'Identity' is required.");

        services.AddDbContext<IdentityDbContext>(
            options =>
                options.UseSqlServer(
                    connectionString,
                    sql =>
                        sql.MigrationsHistoryTable(
                            "__EFMigrationsHistory",
                            "identity")));

        services
            .AddIdentity<
                ApplicationUser,
                IdentityRole<Guid>>(
                options =>
                {
                    options.User.RequireUniqueEmail = true;

                    options.Password.RequiredLength = 12;
                    options.Password.RequireDigit = true;
                    options.Password.RequireLowercase = true;
                    options.Password.RequireUppercase = true;
                    options.Password.RequireNonAlphanumeric = true;

                    options.Lockout.AllowedForNewUsers = true;
                    options.Lockout.MaxFailedAccessAttempts = 5;
                    options.Lockout.DefaultLockoutTimeSpan =
                        TimeSpan.FromMinutes(15);
                })
            .AddEntityFrameworkStores<IdentityDbContext>()
            .AddDefaultTokenProviders();

        services.AddAuthentication()
            .AddScheme<AuthenticationSchemeOptions, MobileApplicationAuthenticationHandler>(
                ApplicationIdentityDefaults.Scheme,
                _ => { });

        services.TryAddSingleton(TimeProvider.System);
        services.AddScoped<IMobileApplicationTokenService, MobileApplicationTokenService>();
        services.AddScoped<IMobileApplicationSessionAuthenticator, MobileApplicationSessionAuthenticator>();
        services.AddScoped<IMobileIdentityService, MobileIdentityService>();
        services.Configure<GoogleFederatedIdentityOptions>(
            configuration.GetSection(GoogleFederatedIdentityOptions.SectionName));
        services.AddSingleton<IGoogleIdTokenVerifier, GoogleIdTokenVerifier>();
        services.AddScoped<IFederatedIdentityTokenValidator, GoogleFederatedIdentityTokenValidator>();
        services.AddScoped<INqrbWebGoogleIdentityValidator, NqrbWebGoogleIdentityValidator>();
        services.AddScoped<IMobileFederatedIdentityService, MobileFederatedIdentityService>();
        services.AddScoped<ICallingParticipantDirectory, CallingParticipantDirectory>();
        services.AddScoped<IApplicationAccountDeletionService, ApplicationAccountDeletionService>();
        services.AddScoped<IPublicNqrbAccountDeletionService, PublicNqrbAccountDeletionService>();
        services.AddScoped<IApplicationMembershipActivityReader, ApplicationMembershipActivityReader>();
        services.AddScoped<ApplicationAccountDeletionProcessor>();
        services.AddHostedService<ApplicationAccountDeletionBackgroundService>();

        services.AddRateLimiter(options =>
        {
            options.AddPolicy(
                MobileAccountDeletionRateLimitPolicy,
                context => RateLimitPartition.GetFixedWindowLimiter(
                    context.User.FindFirstValue(ApplicationIdentityDefaults.MembershipIdClaim)
                        ?? context.Connection.RemoteIpAddress?.ToString()
                        ?? "unknown",
                    _ => new FixedWindowRateLimiterOptions
                    {
                        PermitLimit = 3,
                        Window = TimeSpan.FromMinutes(10),
                        QueueLimit = 0
                    }));
            options.AddPolicy(
                PublicNqrbAccountDeletionRateLimitPolicy,
                context => RateLimitPartition.GetFixedWindowLimiter(
                    context.Connection.RemoteIpAddress?.ToString() ?? "unknown",
                    _ => new FixedWindowRateLimiterOptions
                    {
                        PermitLimit = 5,
                        Window = TimeSpan.FromMinutes(15),
                        QueueLimit = 0
                    }));
        });

        services.ConfigureApplicationCookie(
            options =>
            {
                options.Cookie.Name =
                    "__Host-BotGlobal.Admin";

                options.Cookie.HttpOnly = true;
                options.Cookie.SameSite =
                    SameSiteMode.Lax;

                options.Cookie.SecurePolicy =
                    CookieSecurePolicy.Always;

                options.SlidingExpiration = true;

                options.ExpireTimeSpan =
                    TimeSpan.FromHours(8);

                options.Events =
                    new CookieAuthenticationEvents
                    {
                        OnRedirectToLogin =
                            context =>
                            {
                                context.Response.StatusCode =
                                    StatusCodes.Status401Unauthorized;

                                return Task.CompletedTask;
                            },

                        OnRedirectToAccessDenied =
                            context =>
                            {
                                context.Response.StatusCode =
                                    StatusCodes.Status403Forbidden;

                                return Task.CompletedTask;
                            }
                    };
            });

        services.AddAuthorizationBuilder()
            .AddPolicy(
                IdentityPolicies.Administrator,
                policy =>
                    policy.RequireRole(
                        IdentityRoles.Administrator));

        services.AddAuthorizationBuilder()
            .AddPolicy(
                ApplicationIdentityPolicies.For(BotGlobalApplications.FamilyGames),
                policy =>
                {
                    policy.AddAuthenticationSchemes(ApplicationIdentityDefaults.Scheme);
                    policy.RequireAuthenticatedUser();
                    policy.RequireClaim(
                        ApplicationIdentityDefaults.ApplicationKeyClaim,
                        BotGlobalApplications.FamilyGames);
                });

        services.AddAuthorizationBuilder()
            .AddPolicy(
                ApplicationIdentityPolicies.For(BotGlobalApplications.Nqrb),
                policy =>
                {
                    policy.AddAuthenticationSchemes(ApplicationIdentityDefaults.Scheme);
                    policy.RequireAuthenticatedUser();
                    policy.RequireClaim(
                        ApplicationIdentityDefaults.ApplicationKeyClaim,
                        BotGlobalApplications.Nqrb);
                });

        services.Configure<BootstrapAdminOptions>(
            configuration.GetSection(
                BootstrapAdminOptions.SectionName));

        services.AddScoped<
            IAdministratorDescriptorReader,
            AdministratorDescriptorReader>();

        return services;
    }

    public static IEndpointRouteBuilder
        MapIdentityModuleEndpoints(
            this IEndpointRouteBuilder endpoints)
    {
        return endpoints.MapIdentityEndpoints();
    }

    public static Task InitializeIdentityAsync(
        this WebApplication app,
        CancellationToken cancellationToken = default)
    {
        return app.Services
            .InitializeBootstrapAdminAsync(
                app.Configuration,
                cancellationToken);
    }
}
