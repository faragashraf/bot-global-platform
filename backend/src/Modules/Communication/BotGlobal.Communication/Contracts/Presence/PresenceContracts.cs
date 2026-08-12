namespace BotGlobal.Communication.Contracts.Presence;

public enum PresenceState
{
    Offline = 0,
    Online = 1
}

public sealed record PresenceChangedEvent(
    string UserId,
    PresenceState State,
    DateTimeOffset ChangedAtUtc);
