using SentriCam.Domain.Devices;

namespace SentriCam.Application.Connections;

public interface IDevicePresenceTransitionTracker
{
    bool TryTransition(DeviceId deviceId, DeviceTransportState state);
    void Remove(DeviceId deviceId);
}

public sealed class DevicePresenceTransitionTracker : IDevicePresenceTransitionTracker
{
    private readonly object _gate = new();
    private readonly Dictionary<DeviceId, DeviceTransportState> _states = [];

    public bool TryTransition(DeviceId deviceId, DeviceTransportState state)
    {
        lock (_gate)
        {
            if (_states.GetValueOrDefault(deviceId) == state)
            {
                return false;
            }

            _states[deviceId] = state;
            return true;
        }
    }

    public void Remove(DeviceId deviceId)
    {
        lock (_gate)
        {
            _states.Remove(deviceId);
        }
    }
}
