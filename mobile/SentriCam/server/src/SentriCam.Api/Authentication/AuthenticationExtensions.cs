using System.Text;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.IdentityModel.Tokens;
using SentriCam.Application.Abstractions.Authentication;
using SentriCam.Infrastructure.Authentication;
using SentriCam.BuildingBlocks.Authentication;
using SentriCam.Api.Security;
using System.Security.Claims;
using SentriCam.Domain.Devices;

namespace SentriCam.Api.Authentication;

public static class AuthenticationExtensions
{
    public static IServiceCollection AddSentriCamJwtAuthentication(
        this IServiceCollection services,
        IConfiguration configuration)
    {
        ArgumentNullException.ThrowIfNull(services);
        ArgumentNullException.ThrowIfNull(configuration);

        var options = configuration.GetSection(JwtOptions.SectionName).Get<JwtOptions>()
            ?? throw new InvalidOperationException("JWT configuration is missing.");
        if (options.SigningKey.Length < 32)
        {
            throw new InvalidOperationException("JWT signing key must be at least 32 characters.");
        }

        services.AddHttpContextAccessor();
        services.AddScoped<IDeviceRequestAuthorizer, ClaimsDeviceRequestAuthorizer>();

        services
            .AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
            .AddJwtBearer(jwt =>
            {
                jwt.MapInboundClaims = false;
                jwt.SaveToken = false;
                jwt.TokenValidationParameters = new TokenValidationParameters
                {
                    RequireExpirationTime = true,
                    RequireSignedTokens = true,
                    ValidateIssuer = true,
                    ValidIssuer = options.Issuer,
                    ValidateAudience = true,
                    ValidAudience = options.Audience,
                    ValidateIssuerSigningKey = true,
                    IssuerSigningKey = new SymmetricSecurityKey(
                        Encoding.UTF8.GetBytes(options.SigningKey)),
                    ValidateLifetime = true,
                    ValidAlgorithms = [SecurityAlgorithms.HmacSha256],
                    ClockSkew = TimeSpan.FromMinutes(1),
                    NameClaimType = "sub",
                    RoleClaimType = "role",
                };
                jwt.Events = new JwtBearerEvents
                {
                    OnMessageReceived = context =>
                    {
                        context.HttpContext.Items.TryGetValue(
                            SensitiveRequestLoggingMiddleware.AccessTokenItemKey,
                            out var redactedAccessToken);
                        var accessToken = redactedAccessToken as string;
                        if (string.IsNullOrWhiteSpace(accessToken))
                        {
                            accessToken = context.Request.Query["access_token"].FirstOrDefault();
                        }
                        if (!string.IsNullOrWhiteSpace(accessToken)
                            && (context.HttpContext.Request.Path.StartsWithSegments("/hubs/device")
                                || context.HttpContext.Request.Path.StartsWithSegments("/hubs/monitoring")))
                        {
                            context.Token = accessToken;
                        }

                return Task.CompletedTask;
                    },
                    OnTokenValidated = async context =>
                    {
                        var principal = context.Principal;
                        if (principal is null)
                        {
                            context.Fail("Authenticated principal is unavailable.");
                            return;
                        }
                        if (!string.Equals(
                                principal.FindFirstValue(AuthenticationClaimNames.TokenType),
                                AuthenticationTokenTypes.Device,
                                StringComparison.Ordinal))
                        {
                            return;
                        }

                        if (!Guid.TryParse(
                                principal.FindFirstValue(AuthenticationClaimNames.DeviceId),
                                out var rawDeviceId)
                            || !Guid.TryParse(
                                principal.FindFirstValue(AuthenticationClaimNames.RegistrationId),
                                out var registrationId))
                        {
                            context.Fail("Device credential is missing its registration identity.");
                            return;
                        }

                        var validator = context.HttpContext.RequestServices
                            .GetService<IDeviceCredentialValidator>();
                        if (validator is null)
                        {
                            return;
                        }

                        try
                        {
                            if (!await validator.IsCurrentAsync(
                                    DeviceId.From(rawDeviceId),
                                    registrationId,
                                    context.HttpContext.RequestAborted))
                            {
                                context.Fail("Device credential has been revoked.");
                            }
                        }
                        catch (Exception)
                        {
                            context.Fail("Device credential validation failed.");
                        }
                    },
                };
            });

        services.AddAuthorizationBuilder()
            .AddPolicy(
                AuthorizationPolicies.Device,
                policy => policy
                    .RequireAuthenticatedUser()
                    .RequireClaim(
                        AuthenticationClaimNames.TokenType,
                        AuthenticationTokenTypes.Device)
                    .RequireClaim(AuthenticationClaimNames.DeviceId)
                    .RequireAssertion(context => string.Equals(
                        context.User.FindFirst("sub")?.Value,
                        context.User.FindFirst(AuthenticationClaimNames.DeviceId)?.Value,
                        StringComparison.Ordinal)))
            .AddPolicy(
                AuthorizationPolicies.DeviceControl,
                policy => policy
                    .RequireAuthenticatedUser()
                    .RequireClaim(
                        AuthenticationClaimNames.TokenType,
                        AuthenticationTokenTypes.Operator)
                    .RequireRole(AuthorizationRoles.DeviceOperator))
            .AddPolicy(
                AuthorizationPolicies.DeviceAdministration,
                policy => policy
                    .RequireAuthenticatedUser()
                    .RequireClaim(
                        AuthenticationClaimNames.TokenType,
                        AuthenticationTokenTypes.Operator)
                    .RequireRole(AuthorizationRoles.HubAdministrator));

        return services;
    }
}
