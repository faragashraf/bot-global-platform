using Microsoft.EntityFrameworkCore;
using SentriCam.Application.Abstractions.Persistence;
using SentriCam.Domain.Devices;

namespace SentriCam.Infrastructure.Persistence.Repositories;

public sealed class DeviceRepository(SentriCamDbContext dbContext) : IDeviceRepository
{
    public async Task<IReadOnlyList<Device>> ListAsync(
        CancellationToken cancellationToken = default) =>
        await dbContext.Devices
            .Include(device => device.Capabilities)
            .OrderBy(device => device.DisplayName)
            .ToListAsync(cancellationToken);

    public Task<Device?> GetByIdAsync(
        DeviceId id,
        CancellationToken cancellationToken = default) =>
        dbContext.Devices
            .Include(device => device.Capabilities)
            .SingleOrDefaultAsync(device => device.Id == id, cancellationToken);

    public Task<Device?> GetByInstallationIdAsync(
        string installationId,
        CancellationToken cancellationToken = default) =>
        dbContext.Devices
            .Include(device => device.Capabilities)
            .SingleOrDefaultAsync(
                device => device.Identity.InstallationId == installationId,
                cancellationToken);

    public void Add(Device device)
    {
        ArgumentNullException.ThrowIfNull(device);
        dbContext.Devices.Add(device);
    }
}
