using SentriCam.Domain.Common;

namespace SentriCam.Domain.Devices;

public sealed class DeviceCapability : Entity<Guid>
{
    private DeviceCapability()
    {
    }

    private DeviceCapability(
        Guid id,
        DeviceId deviceId,
        string name,
        string version,
        bool isEnabled,
        DateTimeOffset addedAtUtc)
    {
        Id = id;
        DeviceId = deviceId;
        SetValues(name, version, isEnabled);
        AddedAtUtc = addedAtUtc;
        UpdatedAtUtc = addedAtUtc;
    }

    public DeviceId DeviceId { get; private set; }

    public string Name { get; private set; } = string.Empty;

    public string NormalizedName { get; private set; } = string.Empty;

    public string Version { get; private set; } = string.Empty;

    public bool IsEnabled { get; private set; }

    public DateTimeOffset AddedAtUtc { get; private set; }

    public DateTimeOffset UpdatedAtUtc { get; private set; }

    internal static DeviceCapability Create(
        DeviceId deviceId,
        DeviceCapabilityDefinition definition,
        DateTimeOffset now) =>
        new(Guid.NewGuid(), deviceId, definition.Name, definition.Version, definition.IsEnabled, now);

    internal void Update(DeviceCapabilityDefinition definition, DateTimeOffset now)
    {
        SetValues(definition.Name, definition.Version, definition.IsEnabled);
        UpdatedAtUtc = now;
    }

    internal void Disable(DateTimeOffset now)
    {
        IsEnabled = false;
        UpdatedAtUtc = now;
    }

    private void SetValues(string name, string version, bool isEnabled)
    {
        Name = DomainGuard.Required(name, 100, nameof(Name));
        NormalizedName = Name.ToUpperInvariant();
        Version = DomainGuard.Required(version, 50, nameof(Version));
        IsEnabled = isEnabled;
    }
}

public sealed record DeviceCapabilityDefinition(string Name, string Version, bool IsEnabled);
