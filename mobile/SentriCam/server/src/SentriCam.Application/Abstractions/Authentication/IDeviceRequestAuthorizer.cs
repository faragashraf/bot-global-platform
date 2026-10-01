using SentriCam.Domain.Devices;

namespace SentriCam.Application.Abstractions.Authentication;

public interface IDeviceRequestAuthorizer
{
    void EnsureCanAccess(DeviceId deviceId);
}
