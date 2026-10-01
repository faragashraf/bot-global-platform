using SentriCam.Application.Abstractions.Authentication;
using SentriCam.Application.Abstractions.Persistence;
using SentriCam.Application.Common;
using SentriCam.Contracts.Commands;
using SentriCam.Domain.Devices;

namespace SentriCam.Application.Commands;

public sealed class GetStatusCommandHandler(
    IDeviceRepository deviceRepository,
    IDeviceRequestAuthorizer requestAuthorizer)
    : IGetStatusCommandHandler
{
    public async Task<DeviceStatusResult> HandleAsync(
        GetStatus command,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(command);
        var deviceId = DeviceId.From(command.DeviceId);
        requestAuthorizer.EnsureCanAccess(deviceId);
        var device = await deviceRepository.GetByIdAsync(
                deviceId,
                cancellationToken)
            ?? throw new ResourceNotFoundException(nameof(Device), command.DeviceId);

        return new DeviceStatusResult(
            device.Id.Value,
            MapStatus(device.Status),
            device.LastSeenAtUtc,
            device.IsEnabled);
    }

    private static DeviceOperationalStatus MapStatus(DeviceStatus status) => status switch
    {
        DeviceStatus.Registered => DeviceOperationalStatus.Registered,
        DeviceStatus.Offline => DeviceOperationalStatus.Offline,
        DeviceStatus.Online => DeviceOperationalStatus.Online,
        DeviceStatus.Monitoring => DeviceOperationalStatus.Monitoring,
        DeviceStatus.Recording => DeviceOperationalStatus.Recording,
        DeviceStatus.Disabled => DeviceOperationalStatus.Disabled,
        _ => throw new ArgumentOutOfRangeException(nameof(status), status, "Unknown device status."),
    };
}
