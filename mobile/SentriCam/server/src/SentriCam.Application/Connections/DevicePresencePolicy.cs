namespace SentriCam.Application.Connections;

public static class DevicePresencePolicy
{
    public static readonly TimeSpan TransportPulseInterval = TimeSpan.FromSeconds(1);
    public static readonly TimeSpan TransportLostAfter = TimeSpan.FromSeconds(2);
    public static readonly TimeSpan OfflineAfter = TimeSpan.FromSeconds(25);
    public static readonly TimeSpan SweepInterval = TimeSpan.FromSeconds(1);
    public static readonly TimeSpan HealthFreshness = TimeSpan.FromSeconds(30);
}
