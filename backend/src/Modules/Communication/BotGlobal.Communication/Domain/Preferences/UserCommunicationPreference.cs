using BotGlobal.Communication.Domain.Identity;
namespace BotGlobal.Communication.Domain.Preferences;

public sealed class UserCommunicationPreference
{
    private UserCommunicationPreference()
    {
    }

    private UserCommunicationPreference(
        string userId,
        DateTimeOffset updatedAtUtc)
    {
        UserId = ExternalUserId.Normalize(
            userId,
            nameof(userId));
        AllowVoiceCalls = false;
        AllowVideoCalls = false;
        UpdatedAtUtc = updatedAtUtc;
    }

    public string UserId { get; private set; } = string.Empty;

    public bool AllowVoiceCalls { get; private set; }

    public bool AllowVideoCalls { get; private set; }

    public DateTimeOffset UpdatedAtUtc { get; private set; }

    public static UserCommunicationPreference CreateDefault(
        string userId,
        DateTimeOffset updatedAtUtc)
    {
        return new UserCommunicationPreference(
            userId,
            updatedAtUtc);
    }

    public void SetCallPreferences(
        bool allowVoiceCalls,
        bool allowVideoCalls,
        DateTimeOffset updatedAtUtc)
    {
        if (updatedAtUtc < UpdatedAtUtc)
        {
            throw new ArgumentOutOfRangeException(
                nameof(updatedAtUtc),
                "Preference update time cannot move backwards.");
        }

        AllowVoiceCalls = allowVoiceCalls;
        AllowVideoCalls = allowVideoCalls;
        UpdatedAtUtc = updatedAtUtc;
    }
}
