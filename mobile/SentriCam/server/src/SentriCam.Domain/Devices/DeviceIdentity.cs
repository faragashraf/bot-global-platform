using SentriCam.Domain.Common;

namespace SentriCam.Domain.Devices;

public sealed class DeviceIdentity
{
    private DeviceIdentity()
    {
    }

    private DeviceIdentity(
        string installationId,
        string manufacturer,
        string model,
        string platform,
        string operatingSystemVersion,
        string appVersion)
    {
        InstallationId = installationId;
        Manufacturer = manufacturer;
        Model = model;
        Platform = platform;
        OperatingSystemVersion = operatingSystemVersion;
        AppVersion = appVersion;
    }

    public string InstallationId { get; private set; } = string.Empty;

    public string Manufacturer { get; private set; } = string.Empty;

    public string Model { get; private set; } = string.Empty;

    public string Platform { get; private set; } = string.Empty;

    public string OperatingSystemVersion { get; private set; } = string.Empty;

    public string AppVersion { get; private set; } = string.Empty;

    public static DeviceIdentity Create(
        string installationId,
        string manufacturer,
        string model,
        string platform,
        string operatingSystemVersion,
        string appVersion) =>
        new(
            DomainGuard.Required(installationId, 128, nameof(InstallationId)),
            DomainGuard.Required(manufacturer, 100, nameof(Manufacturer)),
            DomainGuard.Required(model, 100, nameof(Model)),
            DomainGuard.Required(platform, 50, nameof(Platform)),
            DomainGuard.Required(operatingSystemVersion, 50, nameof(OperatingSystemVersion)),
            DomainGuard.Required(appVersion, 50, nameof(AppVersion)));
}
