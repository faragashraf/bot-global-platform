using SentriCam.Application.Time;

namespace SentriCam.Tests.Application;

public sealed class HubTimeServiceTests
{
    [Fact]
    public void ReturnsAnAbsoluteUtcInstantAndTheHubTimezoneSeparately()
    {
        var now = new DateTimeOffset(2026, 8, 2, 12, 0, 0, TimeSpan.Zero);
        var result = new HubTimeService(new FixedTimeProvider(now)).GetCurrent();

        Assert.Equal(now, result.ServerUtcNow);
        Assert.Equal(TimeSpan.Zero, result.ServerUtcNow.Offset);
        Assert.Equal(TimeZoneInfo.Local.Id, result.ServerTimeZoneId);
        Assert.Equal(
            (int)TimeZoneInfo.Local.GetUtcOffset(now.UtcDateTime).TotalMinutes,
            result.ServerUtcOffsetMinutes);
    }

    private sealed class FixedTimeProvider(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }
}
