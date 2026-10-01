using SentriCam.Domain.Common;

namespace SentriCam.Domain.Devices;

public enum DeviceRegistrationKind
{
    Initial = 1,
    Renewal = 2,
}

public sealed class DeviceRegistration : AggregateRoot<Guid>
{
    private DeviceRegistration()
    {
    }

    private DeviceRegistration(
        Guid id,
        DeviceId deviceId,
        DeviceRegistrationKind kind,
        string clientAppVersion,
        DateTimeOffset registeredAtUtc)
    {
        Id = id;
        DeviceId = deviceId;
        Kind = kind;
        ClientAppVersion = clientAppVersion;
        RegisteredAtUtc = registeredAtUtc;
    }

    public DeviceId DeviceId { get; private set; }

    public DeviceRegistrationKind Kind { get; private set; }

    public string ClientAppVersion { get; private set; } = string.Empty;

    public DateTimeOffset RegisteredAtUtc { get; private set; }

    public static DeviceRegistration Create(
        DeviceId deviceId,
        DeviceRegistrationKind kind,
        string clientAppVersion,
        DateTimeOffset now) =>
        new(
            Guid.NewGuid(),
            deviceId,
            kind,
            DomainGuard.Required(clientAppVersion, 50, nameof(ClientAppVersion)),
            now);
}
