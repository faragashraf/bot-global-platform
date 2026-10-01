using SentriCam.Application.Abstractions.Authentication;
using SentriCam.Application.Abstractions.Persistence;
using SentriCam.Domain.Devices;

namespace SentriCam.Application.Registration;

public sealed class DeviceCredentialValidator(
    IDeviceRepository devices,
    IDeviceRegistrationRepository registrations) : IDeviceCredentialValidator
{
    public async Task<bool> IsCurrentAsync(
        DeviceId deviceId,
        Guid registrationId,
        CancellationToken cancellationToken = default)
    {
        var device = await devices.GetByIdAsync(deviceId, cancellationToken);
        if (device is null || !device.IsEnabled)
        {
            return false;
        }

        var latest = await registrations.GetLatestAsync(deviceId, cancellationToken);
        return latest?.Id == registrationId;
    }
}
