using Microsoft.EntityFrameworkCore;
using SentriCam.Application.Abstractions.Persistence;
using SentriCam.Domain.Devices;

namespace SentriCam.Infrastructure.Persistence.Repositories;

public sealed class DeviceCommandRepository(SentriCamDbContext dbContext) : IDeviceCommandRepository
{
    public async Task<IReadOnlyList<DeviceCommand>> GetRecentAsync(
        DeviceId deviceId,
        int count,
        CancellationToken cancellationToken = default) =>
        await dbContext.DeviceCommands
            .Where(command => command.DeviceId == deviceId)
            .OrderByDescending(command => command.RequestedAtUtc)
            .Take(count)
            .ToListAsync(cancellationToken);

    public async Task<IReadOnlyList<DeviceCommand>> GetExpiredAsync(
        DateTimeOffset utcNow,
        CancellationToken cancellationToken = default) =>
        await dbContext.DeviceCommands
            .Where(command =>
                (command.State == DeviceCommandState.Pending
                    || command.State == DeviceCommandState.Dispatched)
                && command.ExpiresAtUtc <= utcNow)
            .ToListAsync(cancellationToken);

    public async Task<IReadOnlyList<DeviceCommand>> GetActiveAsync(
        DeviceId deviceId,
        CancellationToken cancellationToken = default) =>
        await dbContext.DeviceCommands
            .Where(command => command.DeviceId == deviceId
                && (command.State == DeviceCommandState.Pending
                    || command.State == DeviceCommandState.Dispatched))
            .ToListAsync(cancellationToken);

    public Task<DeviceCommand?> GetByIdAsync(
        Guid id,
        CancellationToken cancellationToken = default) =>
        dbContext.DeviceCommands.SingleOrDefaultAsync(command => command.Id == id, cancellationToken);

    public Task<DeviceCommand?> GetByCorrelationIdAsync(
        DeviceId deviceId,
        string correlationId,
        CancellationToken cancellationToken = default) =>
        dbContext.DeviceCommands.SingleOrDefaultAsync(
            command => command.DeviceId == deviceId && command.CorrelationId == correlationId,
            cancellationToken);

    public void Add(DeviceCommand command)
    {
        ArgumentNullException.ThrowIfNull(command);
        dbContext.DeviceCommands.Add(command);
    }
}
