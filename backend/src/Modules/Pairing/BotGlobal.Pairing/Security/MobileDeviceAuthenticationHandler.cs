using System.Security.Claims;
using System.Text.Encodings.Web;
using BotGlobal.Contracts.Mobile;
using Microsoft.AspNetCore.Authentication;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace BotGlobal.Pairing.Security;

public sealed class MobileDeviceAuthenticationHandler(
    IOptionsMonitor<AuthenticationSchemeOptions> options,
    ILoggerFactory logger,
    UrlEncoder encoder,
    IMobileDeviceAuthenticator authenticator)
    : AuthenticationHandler<AuthenticationSchemeOptions>(
        options,
        logger,
        encoder)
{
    protected override async Task<AuthenticateResult>
        HandleAuthenticateAsync()
    {
        var credential =
            TryReadCredential();

        if (string.IsNullOrWhiteSpace(credential))
        {
            return AuthenticateResult.NoResult();
        }

        var device =
            await authenticator.AuthenticateAsync(
                credential,
                Context.RequestAborted);

        if (device is null)
        {
            return AuthenticateResult.Fail(
                "Invalid or revoked mobile device credential.");
        }

        var claims = new List<Claim>
        {
            new(
                MobileDeviceAuthenticationDefaults.DeviceIdClaim,
                device.DeviceId.ToString()),

            new(
                MobileDeviceAuthenticationDefaults.PlatformClientIdClaim,
                device.PlatformClientId.ToString())
        };

        if (!string.IsNullOrWhiteSpace(
                device.ExternalSubjectId))
        {
            claims.Add(
                new Claim(
                    MobileDeviceAuthenticationDefaults.ExternalSubjectIdClaim,
                    device.ExternalSubjectId));
        }

        var identity =
            new ClaimsIdentity(
                claims,
                MobileDeviceAuthenticationDefaults.Scheme);

        var principal =
            new ClaimsPrincipal(identity);

        var ticket =
            new AuthenticationTicket(
                principal,
                MobileDeviceAuthenticationDefaults.Scheme);

        return AuthenticateResult.Success(ticket);
    }

    private string? TryReadCredential()
    {
        var authorization =
            Request.Headers.Authorization.ToString();

        const string devicePrefix = "Device ";
        const string bearerPrefix = "Bearer ";

        if (authorization.StartsWith(
                devicePrefix,
                StringComparison.OrdinalIgnoreCase))
        {
            return authorization[devicePrefix.Length..]
                .Trim();
        }

        // SignalR native clients commonly use an access-token provider,
        // which produces a Bearer token.
        if (authorization.StartsWith(
                bearerPrefix,
                StringComparison.OrdinalIgnoreCase))
        {
            return authorization[bearerPrefix.Length..]
                .Trim();
        }

        // During WebSocket/SSE transport negotiation SignalR may place the
        // access token in the query string.
        if (Request.Path.StartsWithSegments(
                MobileNotificationRealtimeContract.HubPath)
            && Request.Query.TryGetValue(
                "access_token",
                out var accessToken))
        {
            return accessToken.ToString().Trim();
        }

        return null;
    }
}
