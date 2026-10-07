using BotGlobal.Contracts.Mobile;

namespace BotGlobal.Identity.Application.MobileVersionPolicies;

public sealed record MobileVersionPolicyResponse(
    string CurrentVersion,
    string LatestVersion,
    string MinimumSupportedVersion,
    string? Message,
    string? StoreDestination);

public sealed record MobileVersionPolicyAdminItem(
    string ApplicationKey,
    string ApplicationName,
    string Platform,
    string LatestVersion,
    string MinimumSupportedVersion,
    string? Message,
    string? StoreDestination,
    bool IsActive,
    bool IsPersisted,
    DateTimeOffset? UpdatedAtUtc,
    string? UpdatedByDisplayName);

public sealed record UpdateMobileVersionPolicyRequest(
    string LatestVersion,
    string MinimumSupportedVersion,
    string? Message,
    string? StoreDestination,
    bool IsActive);

public sealed record MobileVersionPolicyEditor(
    Guid? UserId,
    string? DisplayName);

internal static class MobileVersionPolicyCatalog
{
    public static readonly IReadOnlyList<(string ApplicationKey, string Platform)> KnownTargets =
    [
        (BotGlobalApplications.Nqrb, "android"),
        (BotGlobalApplications.FamilyGames, "android"),
        (BotGlobalApplications.FamilyGames, "ios")
    ];

    public static string ApplicationName(string applicationKey) =>
        applicationKey switch
        {
            BotGlobalApplications.Nqrb => "NQRB",
            BotGlobalApplications.FamilyGames => "Family Games",
            _ => applicationKey
        };

    public static bool IsKnownTarget(string applicationKey, string platform) =>
        KnownTargets.Any(target =>
            string.Equals(target.ApplicationKey, applicationKey, StringComparison.OrdinalIgnoreCase)
            && string.Equals(target.Platform, platform, StringComparison.OrdinalIgnoreCase));

    public static string NormalizeApplicationKey(string applicationKey) =>
        applicationKey.Trim().ToLowerInvariant();

    public static string NormalizePlatform(string platform) =>
        platform.Trim().ToLowerInvariant();
}
