namespace BotGlobal.Communication.Application.Presence;

public sealed class PresenceOptions
{
    public const string SectionName = "Presence";
    public int LeaseSeconds { get; set; } = 90;
    public int HeartbeatSeconds { get; set; } = 20;
    public int FreshnessSeconds { get; set; } = 40;
    public int MaxEligibleSessions { get; set; } = 16;
    public int MaxConnectionsPerSession { get; set; } = 16;
    public int MaxProviderResponseBytes { get; set; } = 64 * 1024;

    public bool IsValid() => LeaseSeconds == 90 && HeartbeatSeconds == 20 &&
        FreshnessSeconds == 40 && MaxEligibleSessions is >= 1 and <= 32 &&
        MaxConnectionsPerSession is >= 1 and <= 32 &&
        MaxProviderResponseBytes is >= 4096 and <= 262144;
}
