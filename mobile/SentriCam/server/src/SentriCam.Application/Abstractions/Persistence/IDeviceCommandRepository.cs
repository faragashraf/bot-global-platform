using SentriCam.Domain.Devices;

namespace SentriCam.Application.Abstractions.Persistence;

public interface IDeviceCommandRepository
{
    Task<IReadOnlyList<DeviceCommand>> GetRecentAsync(
        DeviceId deviceId,
        int count,
        CancellationToken cancellationToken = default);

    Task<IReadOnlyList<DeviceCommand>> GetExpiredAsync(
        DateTimeOffset utcNow,
        CancellationToken cancellationToken = default);

    Task<IReadOnlyList<DeviceCommand>> GetActiveAsync(
        DeviceId deviceId,
        CancellationToken cancellationToken = default);

    Task<DeviceCommand?> GetByIdAsync(Guid id, CancellationToken cancellationToken = default);

    Task<DeviceCommand?> GetByCorrelationIdAsync(
        DeviceId deviceId,
        string correlationId,
        CancellationToken cancellationToken = default);

    void Add(DeviceCommand command);
}
