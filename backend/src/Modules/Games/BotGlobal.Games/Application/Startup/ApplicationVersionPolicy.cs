using Microsoft.Extensions.Options;

namespace BotGlobal.Games.Application.Startup;

public sealed class FamilyGamesVersionPolicyOptions
{
    public const string SectionName = "FamilyGames:VersionPolicy";
    public PlatformVersionPolicy Android { get; set; } = new();
    public PlatformVersionPolicy Ios { get; set; } = new();
}

public sealed class PlatformVersionPolicy
{
    public string LatestVersion { get; set; } = "0.1.0";
    public string MinimumSupportedVersion { get; set; } = "0.1.0";
    public string? Message { get; set; }
    public string? StoreDestination { get; set; }
}

public sealed record ApplicationVersionPolicyResponse(
    string CurrentVersion,
    string LatestVersion,
    string MinimumSupportedVersion,
    string? Message,
    string? StoreDestination);

internal sealed class ApplicationVersionPolicyReader(
    IOptions<FamilyGamesVersionPolicyOptions> options)
{
    public ApplicationVersionPolicyResponse Read(string platform, string currentVersion)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(currentVersion);
        var policy = string.Equals(platform, "ios", StringComparison.OrdinalIgnoreCase)
            ? options.Value.Ios
            : options.Value.Android;
        return new ApplicationVersionPolicyResponse(
            currentVersion.Trim(),
            policy.LatestVersion,
            policy.MinimumSupportedVersion,
            policy.Message,
            policy.StoreDestination);
    }
}
