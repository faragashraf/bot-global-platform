using System.Text.RegularExpressions;
using BotGlobal.Identity.Domain;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;

namespace BotGlobal.Identity.Application.MobileVersionPolicies;

public interface IMobileVersionPolicyAdminService
{
    Task<IReadOnlyList<MobileVersionPolicyAdminItem>> ListAsync(
        CancellationToken cancellationToken = default);

    Task<MobileVersionPolicyAdminItem> UpsertAsync(
        string applicationKey,
        string platform,
        UpdateMobileVersionPolicyRequest request,
        MobileVersionPolicyEditor editor,
        CancellationToken cancellationToken = default);
}

internal sealed partial class MobileVersionPolicyAdminService(
    IdentityDbContext dbContext,
    IConfiguration configuration,
    TimeProvider timeProvider) : IMobileVersionPolicyAdminService
{
    public async Task<IReadOnlyList<MobileVersionPolicyAdminItem>> ListAsync(
        CancellationToken cancellationToken = default)
    {
        var persisted = await dbContext.MobileVersionPolicies
            .AsNoTracking()
            .ToDictionaryAsync(
                policy => (policy.ApplicationKey, policy.Platform),
                cancellationToken);

        return MobileVersionPolicyCatalog.KnownTargets
            .Select(target => persisted.TryGetValue(target, out var policy)
                ? ToAdminItem(policy, isPersisted: true)
                : FallbackItem(target.ApplicationKey, target.Platform))
            .OrderBy(item => item.ApplicationName)
            .ThenBy(item => item.Platform)
            .ToArray();
    }

    public async Task<MobileVersionPolicyAdminItem> UpsertAsync(
        string applicationKey,
        string platform,
        UpdateMobileVersionPolicyRequest request,
        MobileVersionPolicyEditor editor,
        CancellationToken cancellationToken = default)
    {
        var normalizedApplicationKey = MobileVersionPolicyCatalog.NormalizeApplicationKey(applicationKey);
        var normalizedPlatform = MobileVersionPolicyCatalog.NormalizePlatform(platform);
        if (!MobileVersionPolicyCatalog.IsKnownTarget(normalizedApplicationKey, normalizedPlatform))
        {
            throw new KeyNotFoundException("The requested application/platform policy is not supported.");
        }

        var latest = ValidateVersion(nameof(request.LatestVersion), request.LatestVersion);
        var minimum = ValidateVersion(nameof(request.MinimumSupportedVersion), request.MinimumSupportedVersion);
        var message = NormalizeOptional(request.Message, 500, nameof(request.Message));
        var storeDestination = NormalizeStoreDestination(request.StoreDestination);
        var now = timeProvider.GetUtcNow();

        var policy = await dbContext.MobileVersionPolicies.SingleOrDefaultAsync(
            item => item.ApplicationKey == normalizedApplicationKey
                && item.Platform == normalizedPlatform,
            cancellationToken);

        if (policy is null)
        {
            policy = new MobileVersionPolicy
            {
                ApplicationKey = normalizedApplicationKey,
                Platform = normalizedPlatform,
                CreatedAtUtc = now
            };
            dbContext.MobileVersionPolicies.Add(policy);
        }

        policy.LatestVersion = latest;
        policy.MinimumSupportedVersion = minimum;
        policy.Message = message;
        policy.StoreDestination = storeDestination;
        policy.IsActive = request.IsActive;
        policy.UpdatedAtUtc = now;
        policy.UpdatedByUserId = editor.UserId;
        policy.UpdatedByDisplayName = NormalizeOptional(editor.DisplayName, 200, nameof(editor.DisplayName));

        await dbContext.SaveChangesAsync(cancellationToken);
        return ToAdminItem(policy, isPersisted: true);
    }

    private MobileVersionPolicyAdminItem FallbackItem(string applicationKey, string platform)
    {
        var sectionPath = (applicationKey, platform) switch
        {
            ("nqrb", "android") => "Nqrb:VersionPolicy:Android",
            ("family-games", "android") => "FamilyGames:VersionPolicy:Android",
            ("family-games", "ios") => "FamilyGames:VersionPolicy:Ios",
            _ => null
        };
        var section = sectionPath is null ? null : configuration.GetSection(sectionPath);

        return new MobileVersionPolicyAdminItem(
            applicationKey,
            MobileVersionPolicyCatalog.ApplicationName(applicationKey),
            platform,
            section?["LatestVersion"]?.Trim() ?? "0.0.0",
            section?["MinimumSupportedVersion"]?.Trim() ?? "0.0.0",
            section?["Message"],
            section?["StoreDestination"],
            IsActive: true,
            IsPersisted: false,
            UpdatedAtUtc: null,
            UpdatedByDisplayName: null);
    }

    private static MobileVersionPolicyAdminItem ToAdminItem(
        MobileVersionPolicy policy,
        bool isPersisted) =>
        new(
            policy.ApplicationKey,
            MobileVersionPolicyCatalog.ApplicationName(policy.ApplicationKey),
            policy.Platform,
            policy.LatestVersion,
            policy.MinimumSupportedVersion,
            policy.Message,
            policy.StoreDestination,
            policy.IsActive,
            isPersisted,
            policy.UpdatedAtUtc,
            policy.UpdatedByDisplayName);

    private static string ValidateVersion(string fieldName, string value)
    {
        if (string.IsNullOrWhiteSpace(value) || !SemanticVersionPattern().IsMatch(value.Trim()))
        {
            throw new ArgumentException("Enter a semantic version such as 0.2.7.", fieldName);
        }

        return value.Trim();
    }

    private static string? NormalizeStoreDestination(string? value)
    {
        var normalized = NormalizeOptional(value, 500, nameof(UpdateMobileVersionPolicyRequest.StoreDestination));
        if (normalized is null) return null;
        if (!Uri.TryCreate(normalized, UriKind.Absolute, out _))
        {
            throw new ArgumentException("Store destination must be an absolute URL.", nameof(UpdateMobileVersionPolicyRequest.StoreDestination));
        }

        return normalized;
    }

    private static string? NormalizeOptional(string? value, int maxLength, string fieldName)
    {
        var normalized = value?.Trim();
        if (string.IsNullOrEmpty(normalized)) return null;
        if (normalized.Length > maxLength)
        {
            throw new ArgumentException($"Value must be {maxLength} characters or fewer.", fieldName);
        }

        return normalized;
    }

    [GeneratedRegex(@"^\d+(?:\.\d+){0,3}(?:-[A-Za-z0-9][A-Za-z0-9.-]*)?$", RegexOptions.CultureInvariant)]
    private static partial Regex SemanticVersionPattern();
}
