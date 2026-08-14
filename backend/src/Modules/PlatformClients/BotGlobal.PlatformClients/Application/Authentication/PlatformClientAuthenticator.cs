using BotGlobal.PlatformClients.Application.Security;
using BotGlobal.PlatformClients.Domain;

namespace BotGlobal.PlatformClients.Application.Authentication;

public sealed class PlatformClientAuthenticator(
    IPlatformClientAuthenticationStore store,
    IPlatformClientSecretService secretService)
    : IPlatformClientAuthenticator
{
    public async Task<PlatformClientAuthenticationResult?> AuthenticateAsync(
        string clientKey,
        string clientSecret,
        DateTimeOffset utcNow,
        CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(clientKey)
            || string.IsNullOrWhiteSpace(clientSecret))
        {
            return null;
        }

        string normalizedClientKey;

        try
        {
            normalizedClientKey = PlatformClient.NormalizeClientKey(clientKey);
        }
        catch (ArgumentException)
        {
            return null;
        }

        var snapshot = await store.FindByClientKeyAsync(
            normalizedClientKey,
            cancellationToken);

        if (snapshot is null
            || !snapshot.IsActive
            || !string.Equals(
                snapshot.ClientKey,
                normalizedClientKey,
                StringComparison.Ordinal))
        {
            return null;
        }

        var matched = false;

        foreach (var credential in snapshot.Credentials)
        {
            if (!credential.IsUsableAt(utcNow))
            {
                continue;
            }

            matched |= secretService.Verify(
                clientSecret,
                credential.SecretHash);
        }

        if (!matched)
        {
            return null;
        }

        return new PlatformClientAuthenticationResult(
            snapshot.ClientId,
            snapshot.ClientKey,
            snapshot.Capabilities
                .Distinct(StringComparer.Ordinal)
                .Order(StringComparer.Ordinal)
                .ToArray());
    }
}
