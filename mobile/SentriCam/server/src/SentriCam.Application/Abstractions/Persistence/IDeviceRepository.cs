using SentriCam.Domain.Devices;

namespace SentriCam.Application.Abstractions.Persistence;

public interface IDeviceRepository
{
    Task<IReadOnlyList<Device>> ListAsync(CancellationToken cancellationToken = default);

    Task<Device?> GetByIdAsync(DeviceId id, CancellationToken cancellationToken = default);

    Task<Device?> GetByInstallationIdAsync(
        string installationId,
        CancellationToken cancellationToken = default);

    void Add(Device device);
}
