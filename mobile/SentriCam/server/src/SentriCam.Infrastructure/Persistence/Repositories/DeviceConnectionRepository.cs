using Microsoft.EntityFrameworkCore;
using SentriCam.Application.Abstractions.Persistence;
using SentriCam.Domain.Devices;

namespace SentriCam.Infrastructure.Persistence.Repositories;

public sealed class DeviceConnectionRepository(SentriCamDbContext dbContext)
    : IDeviceConnectionRepository
{
    public Task<DeviceConnection?> GetActiveAsync(
        DeviceId deviceId,
        CancellationToken cancellationToken = default) =>
        dbContext.DeviceConnections.SingleOrDefaultAsync(
            connection => connection.DeviceId == deviceId && connection.IsActive,
            cancellationToken);

    public Task<DeviceConnection?> GetByTransportConnectionIdAsync(
        string transportConnectionId,
        CancellationToken cancellationToken = default) =>
        dbContext.DeviceConnections.SingleOrDefaultAsync(
            connection => connection.TransportConnectionId == transportConnectionId,
            cancellationToken);

    public void Add(DeviceConnection connection)
    {
        ArgumentNullException.ThrowIfNull(connection);
        dbContext.DeviceConnections.Add(connection);
    }
}
