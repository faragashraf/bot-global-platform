using BotGlobal.Contracts.Mobile;
using BotGlobal.Identity.Application.MobileVersionPolicies;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;

namespace BotGlobal.UnitTests.MobileVersionPolicy;

public sealed class MobileVersionPolicyAdminServiceTests
{
    [Fact]
    public async Task List_includes_known_targets_from_configuration_when_not_persisted()
    {
        await using var db = CreateDbContext();
        var service = new MobileVersionPolicyAdminService(db, Configuration(
            ("Nqrb:VersionPolicy:Android:LatestVersion", "0.2.6"),
            ("Nqrb:VersionPolicy:Android:MinimumSupportedVersion", "0.2.6"),
            ("FamilyGames:VersionPolicy:Android:LatestVersion", "0.1.0"),
            ("FamilyGames:VersionPolicy:Android:MinimumSupportedVersion", "0.1.0"),
            ("FamilyGames:VersionPolicy:Ios:LatestVersion", "0.1.0"),
            ("FamilyGames:VersionPolicy:Ios:MinimumSupportedVersion", "0.1.0")),
            new FakeTimeProvider());

        var items = await service.ListAsync();

        Assert.Equal(3, items.Count);
        Assert.Contains(items, item =>
            item.ApplicationKey == BotGlobalApplications.Nqrb
            && item.Platform == "android"
            && item.LatestVersion == "0.2.6"
            && !item.IsPersisted);
    }

    [Fact]
    public async Task Upsert_persists_policy_with_editor_metadata()
    {
        await using var db = CreateDbContext();
        var now = new DateTimeOffset(2026, 10, 7, 12, 0, 0, TimeSpan.Zero);
        var service = new MobileVersionPolicyAdminService(
            db,
            Configuration(),
            new FakeTimeProvider(now));
        var editorId = Guid.NewGuid();

        var result = await service.UpsertAsync(
            BotGlobalApplications.Nqrb,
            "android",
            new UpdateMobileVersionPolicyRequest(
                "0.2.7",
                "0.2.6",
                "Update available",
                "https://play.google.com/store/apps/details?id=com.botglobal.nqrb",
                IsActive: true),
            new MobileVersionPolicyEditor(editorId, "Admin User"));

        Assert.True(result.IsPersisted);
        Assert.Equal("0.2.7", result.LatestVersion);
        Assert.Equal("0.2.6", result.MinimumSupportedVersion);
        var stored = await db.MobileVersionPolicies.SingleAsync();
        Assert.Equal(editorId, stored.UpdatedByUserId);
        Assert.Equal("Admin User", stored.UpdatedByDisplayName);
        Assert.Equal(now, stored.UpdatedAtUtc);
    }

    [Fact]
    public async Task Upsert_rejects_unknown_targets_and_invalid_versions()
    {
        await using var db = CreateDbContext();
        var service = new MobileVersionPolicyAdminService(db, Configuration(), new FakeTimeProvider());

        await Assert.ThrowsAsync<KeyNotFoundException>(() => service.UpsertAsync(
            "unknown",
            "android",
            new UpdateMobileVersionPolicyRequest("1.0.0", "1.0.0", null, null, true),
            new MobileVersionPolicyEditor(null, null)));

        await Assert.ThrowsAsync<ArgumentException>(() => service.UpsertAsync(
            BotGlobalApplications.Nqrb,
            "android",
            new UpdateMobileVersionPolicyRequest("latest", "1.0.0", null, null, true),
            new MobileVersionPolicyEditor(null, null)));
    }

    private static IdentityDbContext CreateDbContext()
    {
        var options = new DbContextOptionsBuilder<IdentityDbContext>()
            .UseInMemoryDatabase($"mobile-version-policy-admin-{Guid.NewGuid()}")
            .Options;
        return new IdentityDbContext(options);
    }

    private static IConfiguration Configuration(params (string Key, string Value)[] values) =>
        new ConfigurationBuilder()
            .AddInMemoryCollection(values.ToDictionary(item => item.Key, item => (string?)item.Value))
            .Build();

    private sealed class FakeTimeProvider(DateTimeOffset? utcNow = null) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() =>
            utcNow ?? new DateTimeOffset(2026, 10, 7, 0, 0, 0, TimeSpan.Zero);
    }
}
