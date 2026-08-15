namespace BotGlobal.PlatformClients.Application.Capabilities;

public enum PlatformCapabilityImpact
{
    Low = 1,
    Medium = 2,
    High = 3
}

public sealed record PlatformCapabilityDescriptor(
    string Capability,
    string Name,
    string Description,
    string GrantEffect,
    string RevokeEffect,
    PlatformCapabilityImpact Impact);

public interface IPlatformCapabilityCatalog
{
    IReadOnlyList<PlatformCapabilityDescriptor> GetAll();

    bool Contains(string capability);
}

internal sealed class PlatformCapabilityCatalog
    : IPlatformCapabilityCatalog
{
    private static readonly IReadOnlyList<PlatformCapabilityDescriptor>
        Capabilities =
        [
            new(
                "pairing:create",
                "Create mobile pairing challenge",
                "Allows the platform client to create a temporary QR pairing challenge for an authenticated user.",
                "The client can create new QR pairing challenges.",
                "New QR pairing challenges can no longer be created. Existing paired devices are not revoked.",
                PlatformCapabilityImpact.Medium),

            new(
                "pairing:status",
                "Read mobile pairing status",
                "Allows the platform client to check whether a pairing challenge is pending, completed, or expired.",
                "The client can monitor pairing challenge status.",
                "The client can no longer query pairing challenge status.",
                PlatformCapabilityImpact.Low),

            new(
                "notifications:send",
                "Send mobile notifications",
                "Allows the platform client to request notification delivery to mobile devices associated with its own users.",
                "The client can send notification requests to its associated mobile users.",
                "The client can no longer initiate mobile notification delivery.",
                PlatformCapabilityImpact.High),

            new(
                "platform-clients:probe",
                "Probe platform authentication",
                "Allows diagnostic calls used to verify machine authentication and capability authorization.",
                "The client can use the protected platform probe endpoint.",
                "Only protected probe operations are disabled.",
                PlatformCapabilityImpact.Low)
        ];

    public IReadOnlyList<PlatformCapabilityDescriptor> GetAll() =>
        Capabilities;

    public bool Contains(string capability)
    {
        if (string.IsNullOrWhiteSpace(capability))
        {
            return false;
        }

        var normalized = capability.Trim();

        return Capabilities.Any(
            item =>
                string.Equals(
                    item.Capability,
                    normalized,
                    StringComparison.OrdinalIgnoreCase));
    }
}
