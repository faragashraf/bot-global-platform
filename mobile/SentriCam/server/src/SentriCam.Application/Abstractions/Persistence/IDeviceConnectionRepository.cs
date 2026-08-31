using SentriCam.Domain.Devices;

namespace SentriCam.Application.Abstractions.Persistence;

public interface IDeviceConnectionRepository
{
    Task<DeviceConnection?> GetActiveAsync(
        DeviceId deviceId,
        CancellationToken cancellationToken = default);

    Task<DeviceConnection?> GetByTransportConnectionIdAsync(
        string transportConnectionId,
        CancellationToken cancellationToken = default);

    void Add(DeviceConnection connection);
}
