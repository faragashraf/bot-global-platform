using SentriCam.Domain.Devices;

namespace SentriCam.Application.Abstractions.Persistence;

public interface IDeviceRegistrationRepository
{
    Task<DeviceRegistration?> GetLatestAsync(
        DeviceId deviceId,
        CancellationToken cancellationToken = default);

    void Add(DeviceRegistration registration);
}
