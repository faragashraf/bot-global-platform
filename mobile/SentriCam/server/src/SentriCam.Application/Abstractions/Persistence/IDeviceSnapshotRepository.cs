using SentriCam.Domain.Devices;

namespace SentriCam.Application.Abstractions.Persistence;

public interface IDeviceSnapshotRepository
{
    Task<DeviceSnapshot?> GetLatestAsync(
        DeviceId deviceId,
        CancellationToken cancellationToken = default);

    Task<DeviceSnapshot?> GetByVersionAsync(
        DeviceId deviceId,
        long snapshotVersion,
        CancellationToken cancellationToken = default);

    Task<IReadOnlyList<DeviceSnapshot>> GetRecentAsync(
        DeviceId deviceId,
        int count,
        CancellationToken cancellationToken = default);

    void Add(DeviceSnapshot snapshot);
}
