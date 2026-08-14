using System.Security.Claims;
using System.Text.Encodings.Web;
using BotGlobal.PlatformClients.Application.Authentication;
using Microsoft.AspNetCore.Authentication;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace BotGlobal.PlatformClients.Authentication;

public sealed class PlatformClientAuthenticationHandler(
    IOptionsMonitor<AuthenticationSchemeOptions> options,
    ILoggerFactory logger,
    UrlEncoder encoder,
    IPlatformClientAuthenticator authenticator)
    : AuthenticationHandler<AuthenticationSchemeOptions>(
        options,
        logger,
        encoder)
{
    protected override async Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        var hasKey = Request.Headers.TryGetValue(
            PlatformClientAuthenticationDefaults.ClientKeyHeader,
            out var keyValues);

        var hasSecret = Request.Headers.TryGetValue(
            PlatformClientAuthenticationDefaults.ClientSecretHeader,
            out var secretValues);

        if (!hasKey && !hasSecret)
        {
            return AuthenticateResult.NoResult();
        }

        if (!hasKey || !hasSecret)
        {
            return AuthenticateResult.Fail(
                "Incomplete platform client credentials.");
        }

        var result = await authenticator.AuthenticateAsync(
            keyValues.ToString(),
            secretValues.ToString(),
            DateTimeOffset.UtcNow,
            Context.RequestAborted);

        if (result is null)
        {
            return AuthenticateResult.Fail(
                "Invalid platform client credentials.");
        }

        var claims = new List<Claim>
        {
            new(
                ClaimTypes.NameIdentifier,
                result.ClientId.ToString()),
            new(
                PlatformClientAuthenticationDefaults.ClientIdClaim,
                result.ClientId.ToString()),
            new(
                PlatformClientAuthenticationDefaults.ClientKeyClaim,
                result.ClientKey)
        };

        claims.AddRange(
            result.Capabilities.Select(
                capability => new Claim(
                    PlatformClientAuthenticationDefaults.CapabilityClaim,
                    capability)));

        var identity = new ClaimsIdentity(
            claims,
            PlatformClientAuthenticationDefaults.Scheme);

        return AuthenticateResult.Success(
            new AuthenticationTicket(
                new ClaimsPrincipal(identity),
                Scheme.Name));
    }
}
