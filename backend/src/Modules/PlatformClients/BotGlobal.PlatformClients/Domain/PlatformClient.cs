namespace BotGlobal.PlatformClients.Domain;

public enum PlatformClientStatus
{
    Active = 1,
    Disabled = 2
}

public sealed class PlatformClient
{
    public const int ClientKeyMaxLength = 100;
    public const int DisplayNameMaxLength = 200;

    private readonly List<PlatformClientCredential> _credentials = [];
    private readonly List<PlatformClientCapability> _capabilities = [];

    private PlatformClient() { }

    private PlatformClient(
        Guid id,
        string clientKey,
        string displayName,
        DateTimeOffset createdAtUtc)
    {
        if (id == Guid.Empty)
        {
            throw new ArgumentException("Client id is required.", nameof(id));
        }

        Id = id;
        ClientKey = NormalizeClientKey(clientKey);
        DisplayName = NormalizeDisplayName(displayName);
        Status = PlatformClientStatus.Active;
        CreatedAtUtc = createdAtUtc;
    }

    public Guid Id { get; private set; }
    public string ClientKey { get; private set; } = string.Empty;
    public string DisplayName { get; private set; } = string.Empty;
    public PlatformClientStatus Status { get; private set; }
    public DateTimeOffset CreatedAtUtc { get; private set; }
    public DateTimeOffset? DisabledAtUtc { get; private set; }

    public IReadOnlyCollection<PlatformClientCredential> Credentials => _credentials;
    public IReadOnlyCollection<PlatformClientCapability> Capabilities => _capabilities;

    public static PlatformClient Create(
        string clientKey,
        string displayName,
        DateTimeOffset createdAtUtc)
        => new(Guid.NewGuid(), clientKey, displayName, createdAtUtc);

    public void Disable(DateTimeOffset disabledAtUtc)
    {
        if (disabledAtUtc < CreatedAtUtc)
        {
            throw new ArgumentOutOfRangeException(nameof(disabledAtUtc));
        }

        Status = PlatformClientStatus.Disabled;
        DisabledAtUtc = disabledAtUtc;
    }

    public void Enable()
    {
        Status = PlatformClientStatus.Active;
        DisabledAtUtc = null;
    }

    public PlatformClientCredential AddCredential(
        byte[] secretHash,
        DateTimeOffset createdAtUtc,
        DateTimeOffset? expiresAtUtc)
    {
        if (Status != PlatformClientStatus.Active)
        {
            throw new InvalidOperationException(
                "Credentials cannot be added to a disabled client.");
        }

        var credential = PlatformClientCredential.Create(
            Id,
            secretHash,
            createdAtUtc,
            expiresAtUtc);

        _credentials.Add(credential);
        return credential;
    }

    public void GrantCapability(
        string capability,
        DateTimeOffset grantedAtUtc)
    {
        var normalized = PlatformClientCapability.NormalizeCapability(capability);

        if (_capabilities.Any(item =>
                string.Equals(
                    item.Capability,
                    normalized,
                    StringComparison.Ordinal)))
        {
            return;
        }

        _capabilities.Add(
            PlatformClientCapability.Create(
                Id,
                normalized,
                grantedAtUtc));
    }

    public void RevokeCapability(string capability)
    {
        var normalized =
            PlatformClientCapability.NormalizeCapability(
                capability);

        var existing =
            _capabilities.FirstOrDefault(
                item =>
                    string.Equals(
                        item.Capability,
                        normalized,
                        StringComparison.Ordinal));

        if (existing is null)
        {
            return;
        }

        _capabilities.Remove(existing);
    }

    public bool HasCapability(string capability)
    {
        var normalized = PlatformClientCapability.NormalizeCapability(capability);

        return _capabilities.Any(item =>
            string.Equals(
                item.Capability,
                normalized,
                StringComparison.Ordinal));
    }

    public static string NormalizeClientKey(string clientKey)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(clientKey);

        var normalized = clientKey.Trim().ToLowerInvariant();

        if (normalized.Length > ClientKeyMaxLength || normalized.Length < 3)
        {
            throw new ArgumentException("Client key length is invalid.", nameof(clientKey));
        }

        foreach (var c in normalized)
        {
            if (!(char.IsAsciiLetterOrDigit(c) || c is '-' or '_' or '.'))
            {
                throw new ArgumentException(
                    "Client key may contain only letters, digits, '.', '_' and '-'.",
                    nameof(clientKey));
            }
        }

        return normalized;
    }

    private static string NormalizeDisplayName(string displayName)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(displayName);

        var normalized = displayName.Trim();

        if (normalized.Length > DisplayNameMaxLength)
        {
            throw new ArgumentException("Display name is too long.", nameof(displayName));
        }

        return normalized;
    }
}
