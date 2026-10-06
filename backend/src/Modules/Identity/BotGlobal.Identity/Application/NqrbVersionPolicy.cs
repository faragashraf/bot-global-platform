using Microsoft.Extensions.Options;

namespace BotGlobal.Identity.Application;

public sealed class NqrbVersionPolicyOptions
{
    public const string SectionName = "Nqrb:VersionPolicy";
    public MobilePlatformVersionPolicy Android { get; set; } = new();
}

public sealed class MobilePlatformVersionPolicy
{
    public string LatestVersion { get; set; } = "0.2.5";
    public string MinimumSupportedVersion { get; set; } = "0.2.5";
    public string? Message { get; set; }
    public string? StoreDestination { get; set; }
}

public sealed record NqrbVersionPolicyResponse(
    string CurrentVersion,
    string LatestVersion,
    string MinimumSupportedVersion,
    string? Message,
    string? StoreDestination);

internal sealed class NqrbVersionPolicyReader(
    IOptions<NqrbVersionPolicyOptions> options)
{
    public NqrbVersionPolicyResponse Read(string platform, string currentVersion)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(currentVersion);
        var policy = options.Value.Android;
        return new NqrbVersionPolicyResponse(
            currentVersion.Trim(),
            policy.LatestVersion,
            policy.MinimumSupportedVersion,
            policy.Message,
            policy.StoreDestination);
    }
}
