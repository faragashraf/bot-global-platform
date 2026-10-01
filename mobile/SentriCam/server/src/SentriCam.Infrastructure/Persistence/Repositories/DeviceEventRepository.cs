using SentriCam.Application.Abstractions.Persistence;
using SentriCam.Domain.Devices;

namespace SentriCam.Infrastructure.Persistence.Repositories;

public sealed class DeviceEventRepository(SentriCamDbContext dbContext) : IDeviceEventRepository
{
    public void Add(DeviceEvent deviceEvent)
    {
        ArgumentNullException.ThrowIfNull(deviceEvent);
        dbContext.DeviceEvents.Add(deviceEvent);
    }
}
