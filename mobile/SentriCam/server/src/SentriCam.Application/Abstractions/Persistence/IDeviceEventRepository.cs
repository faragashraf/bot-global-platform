using SentriCam.Domain.Devices;

namespace SentriCam.Application.Abstractions.Persistence;

public interface IDeviceEventRepository
{
    void Add(DeviceEvent deviceEvent);
}
