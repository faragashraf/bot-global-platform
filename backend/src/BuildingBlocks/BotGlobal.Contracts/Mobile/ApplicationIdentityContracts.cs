namespace BotGlobal.Contracts.Mobile;

public static class BotGlobalApplications
{
    public const string FamilyGames = "family-games";
    public const string Nqrb = "nqrb";
}

public static class ApplicationIdentityDefaults
{
    public const string Scheme = "BotGlobal.MobileSession";
    public const string MembershipIdClaim = "botglobal:membership_id";
    public const string ApplicationKeyClaim = "botglobal:application_key";
    public const string GuestClaim = "botglobal:is_guest";
}

public static class ApplicationIdentityPolicies
{
    public static string For(string applicationKey) => $"application:{applicationKey}";
}

public sealed record ApplicationIdentityDescriptor(
    Guid MembershipId,
    Guid? GlobalUserId,
    string SubjectId,
    string ApplicationKey,
    string DisplayName,
    bool IsGuest);
