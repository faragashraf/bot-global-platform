using Microsoft.EntityFrameworkCore;
using SentriCam.Application.Abstractions.Persistence;
using SentriCam.Domain.Devices;

namespace SentriCam.Infrastructure.Persistence.Repositories;

public sealed class DeviceSnapshotRepository(SentriCamDbContext dbContext)
    : IDeviceSnapshotRepository
{
    public Task<DeviceSnapshot?> GetLatestAsync(
        DeviceId deviceId,
        CancellationToken cancellationToken = default) =>
        dbContext.DeviceSnapshots
            .Where(snapshot => snapshot.DeviceId == deviceId)
            .OrderByDescending(snapshot => snapshot.SnapshotVersion)
            .FirstOrDefaultAsync(cancellationToken);

    public Task<DeviceSnapshot?> GetByVersionAsync(
        DeviceId deviceId,
        long snapshotVersion,
        CancellationToken cancellationToken = default) =>
        dbContext.DeviceSnapshots.SingleOrDefaultAsync(
            snapshot => snapshot.DeviceId == deviceId
                && snapshot.SnapshotVersion == snapshotVersion,
            cancellationToken);

    public async Task<IReadOnlyList<DeviceSnapshot>> GetRecentAsync(
        DeviceId deviceId,
        int count,
        CancellationToken cancellationToken = default) =>
        await dbContext.DeviceSnapshots
            .Where(snapshot => snapshot.DeviceId == deviceId)
            .OrderByDescending(snapshot => snapshot.SnapshotVersion)
            .Take(count)
            .ToListAsync(cancellationToken);

    public void Add(DeviceSnapshot snapshot)
    {
        ArgumentNullException.ThrowIfNull(snapshot);
        dbContext.DeviceSnapshots.Add(snapshot);
    }
}
