using System.Security.Claims;
using System.Text.Encodings.Web;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Identity.Application;
using Microsoft.AspNetCore.Authentication;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace BotGlobal.Identity.Infrastructure;

public sealed class MobileApplicationAuthenticationHandler(
    IOptionsMonitor<AuthenticationSchemeOptions> options,
    ILoggerFactory logger,
    UrlEncoder encoder,
    IMobileApplicationSessionAuthenticator authenticator)
    : AuthenticationHandler<AuthenticationSchemeOptions>(options, logger, encoder)
{
    protected override async Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        var token = ReadToken();
        if (string.IsNullOrWhiteSpace(token))
        {
            return AuthenticateResult.NoResult();
        }

        var authenticated = await authenticator.AuthenticateAsync(token, Context.RequestAborted);
        if (authenticated is null)
        {
            return AuthenticateResult.Fail("Invalid, expired, or revoked mobile application session.");
        }

        var descriptor = authenticated.Identity;
        var claims = new List<Claim>
        {
            new(ClaimTypes.NameIdentifier, descriptor.SubjectId),
            new(ClaimTypes.Name, descriptor.DisplayName),
            new(ApplicationIdentityDefaults.MembershipIdClaim, descriptor.MembershipId.ToString()),
            new(ApplicationIdentityDefaults.ApplicationKeyClaim, descriptor.ApplicationKey),
            new(ApplicationIdentityDefaults.GuestClaim, descriptor.IsGuest ? "true" : "false")
        };

        if (descriptor.GlobalUserId.HasValue)
        {
            claims.Add(new Claim(ClaimTypes.Sid, descriptor.GlobalUserId.Value.ToString()));
        }

        var identity = new ClaimsIdentity(claims, ApplicationIdentityDefaults.Scheme);
        var principal = new ClaimsPrincipal(identity);
        return AuthenticateResult.Success(
            new AuthenticationTicket(principal, ApplicationIdentityDefaults.Scheme));
    }

    private string? ReadToken()
    {
        var authorization = Request.Headers.Authorization.ToString();
        const string prefix = "Bearer ";
        if (authorization.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
        {
            return authorization[prefix.Length..].Trim();
        }

        if (Request.Path.StartsWithSegments("/hubs/games") &&
            Request.Query.TryGetValue("access_token", out var queryToken))
        {
            return queryToken.ToString().Trim();
        }

        return null;
    }
}
