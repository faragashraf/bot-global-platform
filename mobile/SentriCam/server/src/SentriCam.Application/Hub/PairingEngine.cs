using System.Collections.Concurrent;
using System.Security.Cryptography;
using SentriCam.Application.Commands;
using SentriCam.Application.Common;
using SentriCam.Contracts.Hub;
using SentriCam.Contracts.Commands;
using SentriCam.Contracts.Registration;

namespace SentriCam.Application.Hub;

public interface IPairingEngine
{
    PairingSession CreateSession();

    Task<RegistrationResult> CompleteAsync(
        CompletePairingRequest request,
        CancellationToken cancellationToken = default);
}

public interface IPairingSessionStore
{
    void Add(string key, DateTimeOffset expiresAt, DateTimeOffset now);

    bool TryConsume(string key, DateTimeOffset now);
}

public sealed class InMemoryPairingSessionStore : IPairingSessionStore
{
    private readonly ConcurrentDictionary<string, DateTimeOffset> _sessions = new(StringComparer.Ordinal);

    public void Add(string key, DateTimeOffset expiresAt, DateTimeOffset now)
    {
        foreach (var item in _sessions.Where(item => item.Value <= now))
        {
            _sessions.TryRemove(item.Key, out _);
        }

        _sessions[key] = expiresAt;
    }

    public bool TryConsume(string key, DateTimeOffset now) =>
        _sessions.TryRemove(key, out var expiresAt) && expiresAt > now;
}

public sealed class PairingEngine(
    IHubSetupStore store,
    IAdvertisedHubUrlResolver advertisedHubUrlResolver,
    IPairingSessionStore sessionStore,
    IRegisterDeviceCommandHandler registrationHandler,
    TimeProvider timeProvider) : IPairingEngine
{
    private const string ProtocolVersion = "1";
    private static readonly TimeSpan Lifetime = TimeSpan.FromMinutes(5);

    public PairingSession CreateSession()
    {
        var configuration = store.Current;
        if (!configuration.IsConfigured)
        {
            throw new ResourceConflictException("Finish Hub setup before pairing a device.");
        }

        var advertisedUri = advertisedHubUrlResolver.Resolve();
        if (!advertisedUri.IsAbsoluteUri
            || !advertisedUri.Scheme.Equals(Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase)
            || !string.IsNullOrEmpty(advertisedUri.UserInfo))
        {
            throw new InvalidOperationException(
                "The advertised Hub URL resolver must provide an absolute HTTPS URL without credentials.");
        }
        var address = advertisedUri.GetComponents(
            UriComponents.SchemeAndServer,
            UriFormat.UriEscaped);
        var code = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
        var now = timeProvider.GetUtcNow();
        var expiresAt = now.Add(Lifetime);
        sessionStore.Add(Hash(code, configuration.HubSecret), expiresAt, now);
        var fingerprint = Fingerprint(configuration.HubSecret);
        var payload = $"sentricam://pair?v={ProtocolVersion}&hub={Uri.EscapeDataString(address)}&code={code}&fp={fingerprint}";
        return new PairingSession(
            payload,
            configuration.Draft.HubName,
            address,
            expiresAt,
            ProtocolVersion,
            fingerprint);
    }

    public async Task<RegistrationResult> CompleteAsync(
        CompletePairingRequest request,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        var configuration = store.Current;
        if (!configuration.IsConfigured)
        {
            throw new ResourceConflictException("Finish Hub setup before pairing a device.");
        }

        var key = Hash(request.PairingCode, configuration.HubSecret);
        if (!sessionStore.TryConsume(key, timeProvider.GetUtcNow()))
        {
            throw new AccessDeniedException("The pairing session is invalid or has expired.");
        }

        var result = await registrationHandler.HandlePairingAsync(
            new RegisterDevice(request.Device),
            cancellationToken);
        return result with { HubFingerprint = Fingerprint(configuration.HubSecret) };
    }

    private static string Hash(string code, string secret) => Convert.ToHexString(
        HMACSHA256.HashData(
            Convert.FromBase64String(secret),
            System.Text.Encoding.UTF8.GetBytes(code)));

    private static string Fingerprint(string secret) => Convert.ToHexString(
        SHA256.HashData(Convert.FromBase64String(secret))).ToLowerInvariant();
}
