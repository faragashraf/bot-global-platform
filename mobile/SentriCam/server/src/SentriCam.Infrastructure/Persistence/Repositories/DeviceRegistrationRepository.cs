using SentriCam.Application.Abstractions.Persistence;
using SentriCam.Domain.Devices;
using Microsoft.EntityFrameworkCore;

namespace SentriCam.Infrastructure.Persistence.Repositories;

public sealed class DeviceRegistrationRepository(SentriCamDbContext dbContext)
    : IDeviceRegistrationRepository
{
    public Task<DeviceRegistration?> GetLatestAsync(
        DeviceId deviceId,
        CancellationToken cancellationToken = default) =>
        dbContext.DeviceRegistrations
            .Where(registration => registration.DeviceId == deviceId)
            .OrderByDescending(registration => registration.RegisteredAtUtc)
            .ThenByDescending(registration => registration.Id)
            .FirstOrDefaultAsync(cancellationToken);

    public void Add(DeviceRegistration registration)
    {
        ArgumentNullException.ThrowIfNull(registration);
        dbContext.DeviceRegistrations.Add(registration);
    }
}
