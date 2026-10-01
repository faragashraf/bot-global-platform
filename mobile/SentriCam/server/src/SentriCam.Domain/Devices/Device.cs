using SentriCam.Domain.Common;
using SentriCam.Domain.Devices.Events;
using SentriCam.Domain.Hierarchy;

namespace SentriCam.Domain.Devices;

public sealed class Device : AggregateRoot<DeviceId>
{
    private readonly List<DeviceCapability> _capabilities = [];

    private Device()
    {
    }

    private Device(
        DeviceId id,
        string displayName,
        DeviceIdentity identity,
        DateTimeOffset registeredAtUtc)
    {
        Id = id;
        DisplayName = NormalizeDisplayName(displayName);
        Identity = identity;
        Status = DeviceStatus.Registered;
        IsEnabled = true;
        RegisteredAtUtc = registeredAtUtc;
        UpdatedAtUtc = registeredAtUtc;
    }

    public string DisplayName { get; private set; } = string.Empty;

    public DeviceIdentity Identity { get; private set; } = null!;

    public DeviceStatus Status { get; private set; }

    public bool IsEnabled { get; private set; }

    public DateTimeOffset RegisteredAtUtc { get; private set; }

    public DateTimeOffset UpdatedAtUtc { get; private set; }

    public DateTimeOffset? LastSeenAtUtc { get; private set; }

    public DeviceGroupId? DeviceGroupId { get; private set; }

    public IReadOnlyCollection<DeviceCapability> Capabilities => _capabilities;

    public static Device Register(
        string displayName,
        DeviceIdentity identity,
        IEnumerable<DeviceCapabilityDefinition> capabilities,
        DateTimeOffset now)
    {
        ArgumentNullException.ThrowIfNull(identity);
        ArgumentNullException.ThrowIfNull(capabilities);

        var device = new Device(DeviceId.New(), displayName, identity, now);
        device.SynchronizeCapabilities(capabilities, now);
        device.RaiseDomainEvent(new DeviceRegisteredDomainEvent(
            device.Id,
            device.Identity.InstallationId,
            now));
        return device;
    }

    public void RefreshRegistration(
        string displayName,
        DeviceIdentity identity,
        IEnumerable<DeviceCapabilityDefinition> capabilities,
        DateTimeOffset now)
    {
        ArgumentNullException.ThrowIfNull(identity);
        ArgumentNullException.ThrowIfNull(capabilities);

        if (!string.Equals(Identity.InstallationId, identity.InstallationId, StringComparison.Ordinal))
        {
            throw new DomainValidationException("An existing device installation id cannot be changed.");
        }

        DisplayName = NormalizeDisplayName(displayName);
        Identity = identity;
        SynchronizeCapabilities(capabilities, now);
        UpdatedAtUtc = now;
    }

    public void MarkSeen(DateTimeOffset now)
    {
        if (!IsEnabled)
        {
            throw new DomainValidationException("A disabled device cannot report activity.");
        }

        LastSeenAtUtc = now;
        if (Status is DeviceStatus.Registered or DeviceStatus.Offline)
        {
            Status = DeviceStatus.Online;
        }

        UpdatedAtUtc = now;
    }

    public void ChangeStatus(DeviceStatus status, DateTimeOffset now)
    {
        if (!IsEnabled && status != DeviceStatus.Disabled)
        {
            throw new DomainValidationException("A disabled device cannot change operational status.");
        }

        var previousStatus = Status;
        Status = status;
        UpdatedAtUtc = now;
        RaiseStatusEvents(previousStatus, status, now);
    }

    public void Disable(DateTimeOffset now)
    {
        var previousStatus = Status;
        IsEnabled = false;
        Status = DeviceStatus.Disabled;
        UpdatedAtUtc = now;
        RaiseStatusEvents(previousStatus, Status, now);
    }

    public void Unpair(DateTimeOffset now)
    {
        Disable(now);
        foreach (var capability in _capabilities)
        {
            capability.Disable(now);
        }
    }

    public void ReactivatePairing(DateTimeOffset now)
    {
        if (IsEnabled)
        {
            return;
        }

        IsEnabled = true;
        Status = DeviceStatus.Registered;
        UpdatedAtUtc = now;
    }

    public void RecordMotionDetected(DateTimeOffset detectedAtUtc) =>
        RaiseDomainEvent(new MotionDetectedDomainEvent(Id, detectedAtUtc));

    public void AssignToGroup(DeviceGroupId deviceGroupId, DateTimeOffset now)
    {
        global::SentriCam.Domain.Hierarchy.DeviceGroupId.From(deviceGroupId.Value);
        DeviceGroupId = deviceGroupId;
        UpdatedAtUtc = now;
    }

    public void RemoveFromGroup(DateTimeOffset now)
    {
        DeviceGroupId = null;
        UpdatedAtUtc = now;
    }

    private static string NormalizeDisplayName(string displayName) =>
        DomainGuard.Required(displayName, 200, nameof(DisplayName));

    private void SynchronizeCapabilities(
        IEnumerable<DeviceCapabilityDefinition> capabilities,
        DateTimeOffset now)
    {
        var definitions = capabilities.ToArray();
        var duplicate = definitions
            .GroupBy(item => DomainGuard.Required(item.Name, 100, nameof(DeviceCapability.Name)).ToUpperInvariant())
            .FirstOrDefault(group => group.Count() > 1);

        if (duplicate is not null)
        {
            throw new DomainValidationException($"Capability '{duplicate.Key}' is duplicated.");
        }

        var incomingNames = definitions
            .Select(item => item.Name.Trim().ToUpperInvariant())
            .ToHashSet(StringComparer.Ordinal);

        foreach (var capability in _capabilities.Where(item => !incomingNames.Contains(item.NormalizedName)))
        {
            capability.Disable(now);
        }

        foreach (var definition in definitions)
        {
            var normalizedName = definition.Name.Trim().ToUpperInvariant();
            var existing = _capabilities.SingleOrDefault(item => item.NormalizedName == normalizedName);
            if (existing is null)
            {
                _capabilities.Add(DeviceCapability.Create(Id, definition, now));
            }
            else
            {
                existing.Update(definition, now);
            }
        }
    }

    private void RaiseStatusEvents(
        DeviceStatus previousStatus,
        DeviceStatus currentStatus,
        DateTimeOffset occurredAtUtc)
    {
        if (previousStatus == currentStatus)
        {
            return;
        }

        if (previousStatus == DeviceStatus.Monitoring)
        {
            RaiseDomainEvent(new MonitoringStoppedDomainEvent(Id, occurredAtUtc));
        }

        if (previousStatus == DeviceStatus.Recording)
        {
            RaiseDomainEvent(new RecordingStoppedDomainEvent(Id, occurredAtUtc));
        }

        if (currentStatus == DeviceStatus.Monitoring)
        {
            RaiseDomainEvent(new MonitoringStartedDomainEvent(Id, occurredAtUtc));
        }

        if (currentStatus == DeviceStatus.Recording)
        {
            RaiseDomainEvent(new RecordingStartedDomainEvent(Id, occurredAtUtc));
        }
    }
}
