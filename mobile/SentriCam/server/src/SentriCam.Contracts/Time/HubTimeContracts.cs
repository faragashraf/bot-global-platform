namespace SentriCam.Contracts.Time;

public sealed record HubTimeSnapshot(
    DateTimeOffset ServerUtcNow,
    int ServerUtcOffsetMinutes,
    string ServerTimeZoneId);
