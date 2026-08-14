namespace BotGlobal.PlatformClients.Application.Authentication;

public sealed record PlatformClientCredentialSnapshot(
    byte[] SecretHash,
    DateTimeOffset? ExpiresAtUtc,
    DateTimeOffset? RevokedAtUtc)
{
    public bool IsUsableAt(DateTimeOffset utcNow)
        => RevokedAtUtc is null
           && (ExpiresAtUtc is null || utcNow < ExpiresAtUtc.Value);
}

public sealed record PlatformClientAuthenticationSnapshot(
    Guid ClientId,
    string ClientKey,
    bool IsActive,
    IReadOnlyCollection<string> Capabilities,
    IReadOnlyCollection<PlatformClientCredentialSnapshot> Credentials);

public sealed record PlatformClientAuthenticationResult(
    Guid ClientId,
    string ClientKey,
    IReadOnlyCollection<string> Capabilities);

public interface IPlatformClientAuthenticationStore
{
    Task<PlatformClientAuthenticationSnapshot?> FindByClientKeyAsync(
        string normalizedClientKey,
        CancellationToken cancellationToken = default);
}

public interface IPlatformClientAuthenticator
{
    Task<PlatformClientAuthenticationResult?> AuthenticateAsync(
        string clientKey,
        string clientSecret,
        DateTimeOffset utcNow,
        CancellationToken cancellationToken = default);
}
