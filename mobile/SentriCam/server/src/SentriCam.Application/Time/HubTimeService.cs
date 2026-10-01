using SentriCam.Contracts.Time;

namespace SentriCam.Application.Time;

public interface IHubTimeService
{
    HubTimeSnapshot GetCurrent();
}

public sealed class HubTimeService(TimeProvider timeProvider) : IHubTimeService
{
    public HubTimeSnapshot GetCurrent()
    {
        var now = timeProvider.GetUtcNow();
        return new HubTimeSnapshot(
            now,
            (int)TimeZoneInfo.Local.GetUtcOffset(now.UtcDateTime).TotalMinutes,
            TimeZoneInfo.Local.Id);
    }
}
