namespace BotGlobal.PlatformClients.Domain;

public sealed class PlatformClientCapability
{
    public const int CapabilityMaxLength = 100;

    private PlatformClientCapability() { }

    private PlatformClientCapability(
        Guid clientId,
        string capability,
        DateTimeOffset grantedAtUtc)
    {
        if (clientId == Guid.Empty)
        {
            throw new ArgumentException("Client id is required.", nameof(clientId));
        }

        ClientId = clientId;
        Capability = NormalizeCapability(capability);
        GrantedAtUtc = grantedAtUtc;
    }

    public Guid ClientId { get; private set; }
    public string Capability { get; private set; } = string.Empty;
    public DateTimeOffset GrantedAtUtc { get; private set; }

    internal static PlatformClientCapability Create(
        Guid clientId,
        string capability,
        DateTimeOffset grantedAtUtc)
        => new(clientId, capability, grantedAtUtc);

    public static string NormalizeCapability(string capability)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(capability);

        var normalized = capability.Trim().ToLowerInvariant();

        if (normalized.Length > CapabilityMaxLength)
        {
            throw new ArgumentException("Capability is too long.", nameof(capability));
        }

        foreach (var c in normalized)
        {
            if (!(char.IsAsciiLetterOrDigit(c) || c is ':' or '.' or '-' or '_'))
            {
                throw new ArgumentException(
                    "Capability contains unsupported characters.",
                    nameof(capability));
            }
        }

        return normalized;
    }
}
