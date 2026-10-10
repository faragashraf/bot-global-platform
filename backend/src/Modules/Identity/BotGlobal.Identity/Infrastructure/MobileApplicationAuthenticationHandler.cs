using System.Security.Claims;
using System.Security.Cryptography;
using System.Text.Encodings.Web;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Communication;
using Microsoft.Extensions.DependencyInjection;
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
        Context.Items[PresenceConnectionCredential.HttpContextItemKey] =
            new PresenceConnectionCredential(
                authenticated.SessionId,
                descriptor.MembershipId,
                descriptor.ApplicationKey,
                Convert.ToHexString(SHA256.HashData(MobileApplicationTokenService.Hash(token))),
                async (services, cancellation) =>
            {
                var current = await services.GetRequiredService<IMobileApplicationSessionAuthenticator>()
                    .AuthenticateAsync(token, cancellation);
                return current is not null && current.SessionId == authenticated.SessionId &&
                    current.Identity == descriptor;
            });
        if (Request.Path == ChatContract.HubPath || Request.Path == ChatContract.HubPath + "/negotiate")
            Context.Items[ChatConnectionCredential.Key(ChatActorMechanism.ApplicationSession)] = new ChatConnectionCredential(async (services, cancellation) =>
            {
                var current = await services.GetRequiredService<IMobileApplicationSessionAuthenticator>().AuthenticateAsync(token, cancellation);
                return current is not null && current.SessionId == authenticated.SessionId && current.Identity == descriptor;
            });
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

        if ((Request.Path.StartsWithSegments("/hubs/games") ||
             Request.Path == ChatContract.HubPath || Request.Path == ChatContract.HubPath + "/negotiate") &&
            Request.Query.TryGetValue("access_token", out var queryToken))
        {
            return queryToken.ToString().Trim();
        }

        return null;
    }
}
