using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;

namespace BotGlobal.Identity.Application.MobileVersionPolicies;

public interface IMobileVersionPolicyReader
{
    Task<MobileVersionPolicyResponse?> ReadAsync(
        string applicationKey,
        string platform,
        string currentVersion,
        CancellationToken cancellationToken = default);
}

internal sealed class MobileVersionPolicyReader(
    IdentityDbContext dbContext,
    IConfiguration configuration) : IMobileVersionPolicyReader
{
    public async Task<MobileVersionPolicyResponse?> ReadAsync(
        string applicationKey,
        string platform,
        string currentVersion,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(applicationKey);
        ArgumentException.ThrowIfNullOrWhiteSpace(platform);
        ArgumentException.ThrowIfNullOrWhiteSpace(currentVersion);

        var normalizedApplicationKey = MobileVersionPolicyCatalog.NormalizeApplicationKey(applicationKey);
        var normalizedPlatform = MobileVersionPolicyCatalog.NormalizePlatform(platform);
        if (!MobileVersionPolicyCatalog.IsKnownTarget(normalizedApplicationKey, normalizedPlatform))
        {
            return null;
        }

        var persisted = await dbContext.MobileVersionPolicies
            .AsNoTracking()
            .SingleOrDefaultAsync(
                policy => policy.ApplicationKey == normalizedApplicationKey
                    && policy.Platform == normalizedPlatform,
                cancellationToken);

        if (persisted is { IsActive: true })
        {
            return new MobileVersionPolicyResponse(
                currentVersion.Trim(),
                persisted.LatestVersion,
                persisted.MinimumSupportedVersion,
                persisted.Message,
                persisted.StoreDestination);
        }

        return ReadFallback(normalizedApplicationKey, normalizedPlatform, currentVersion);
    }

    private MobileVersionPolicyResponse? ReadFallback(
        string applicationKey,
        string platform,
        string currentVersion)
    {
        var sectionPath = (applicationKey, platform) switch
        {
            ("nqrb", "android") => "Nqrb:VersionPolicy:Android",
            ("family-games", "android") => "FamilyGames:VersionPolicy:Android",
            ("family-games", "ios") => "FamilyGames:VersionPolicy:Ios",
            _ => null
        };
        if (sectionPath is null) return null;

        var section = configuration.GetSection(sectionPath);
        var latest = section["LatestVersion"];
        var minimum = section["MinimumSupportedVersion"];
        if (string.IsNullOrWhiteSpace(latest) || string.IsNullOrWhiteSpace(minimum))
        {
            return null;
        }

        return new MobileVersionPolicyResponse(
            currentVersion.Trim(),
            latest.Trim(),
            minimum.Trim(),
            section["Message"],
            section["StoreDestination"]);
    }
}
