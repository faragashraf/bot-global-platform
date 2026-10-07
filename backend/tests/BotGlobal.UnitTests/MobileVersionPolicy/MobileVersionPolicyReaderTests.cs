using BotGlobal.Contracts.Mobile;
using BotGlobal.Identity.Application.MobileVersionPolicies;
using BotGlobal.Identity.Domain;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;

namespace BotGlobal.UnitTests.MobileVersionPolicy;

public sealed class MobileVersionPolicyReaderTests
{
    [Fact]
    public async Task Reads_persisted_policy_before_configuration_fallback()
    {
        await using var db = CreateDbContext();
        db.MobileVersionPolicies.Add(new BotGlobal.Identity.Domain.MobileVersionPolicy
        {
            ApplicationKey = BotGlobalApplications.Nqrb,
            Platform = "android",
            LatestVersion = "0.2.7",
            MinimumSupportedVersion = "0.2.7",
            Message = "Persisted update",
            StoreDestination = "https://play.google.com/store/apps/details?id=com.botglobal.nqrb",
            IsActive = true,
            CreatedAtUtc = DateTimeOffset.UtcNow,
            UpdatedAtUtc = DateTimeOffset.UtcNow
        });
        await db.SaveChangesAsync();

        var reader = new MobileVersionPolicyReader(db, Configuration(
            ("Nqrb:VersionPolicy:Android:LatestVersion", "0.2.6"),
            ("Nqrb:VersionPolicy:Android:MinimumSupportedVersion", "0.2.6")));

        var policy = await reader.ReadAsync(BotGlobalApplications.Nqrb, "android", "0.2.6");

        Assert.NotNull(policy);
        Assert.Equal("0.2.6", policy.CurrentVersion);
        Assert.Equal("0.2.7", policy.LatestVersion);
        Assert.Equal("0.2.7", policy.MinimumSupportedVersion);
        Assert.Equal("Persisted update", policy.Message);
    }

    [Fact]
    public async Task Falls_back_to_configuration_when_policy_is_not_persisted()
    {
        await using var db = CreateDbContext();
        var reader = new MobileVersionPolicyReader(db, Configuration(
            ("Nqrb:VersionPolicy:Android:LatestVersion", "0.2.6"),
            ("Nqrb:VersionPolicy:Android:MinimumSupportedVersion", "0.2.6"),
            ("Nqrb:VersionPolicy:Android:Message", "Update required"),
            ("Nqrb:VersionPolicy:Android:StoreDestination", "market://details?id=com.botglobal.nqrb"),
            ("FamilyGames:VersionPolicy:Ios:LatestVersion", "1.4.0"),
            ("FamilyGames:VersionPolicy:Ios:MinimumSupportedVersion", "1.3.0")));

        var nqrb = await reader.ReadAsync(BotGlobalApplications.Nqrb, "android", "0.2.5");
        var familyGamesIos = await reader.ReadAsync(BotGlobalApplications.FamilyGames, "ios", "1.2.0");

        Assert.NotNull(nqrb);
        Assert.Equal("0.2.6", nqrb.LatestVersion);
        Assert.Equal("0.2.6", nqrb.MinimumSupportedVersion);
        Assert.Equal("Update required", nqrb.Message);
        Assert.Equal("market://details?id=com.botglobal.nqrb", nqrb.StoreDestination);
        Assert.NotNull(familyGamesIos);
        Assert.Equal("1.4.0", familyGamesIos.LatestVersion);
        Assert.Equal("1.3.0", familyGamesIos.MinimumSupportedVersion);
    }

    [Fact]
    public async Task Rejects_unknown_app_keys_and_unsupported_platforms()
    {
        await using var db = CreateDbContext();
        var reader = new MobileVersionPolicyReader(db, Configuration(
            ("Nqrb:VersionPolicy:Android:LatestVersion", "0.2.6"),
            ("Nqrb:VersionPolicy:Android:MinimumSupportedVersion", "0.2.6")));

        Assert.Null(await reader.ReadAsync("unknown", "android", "1.0.0"));
        Assert.Null(await reader.ReadAsync(BotGlobalApplications.Nqrb, "ios", "1.0.0"));
    }

    private static IdentityDbContext CreateDbContext()
    {
        var options = new DbContextOptionsBuilder<IdentityDbContext>()
            .UseInMemoryDatabase($"mobile-version-policy-{Guid.NewGuid()}")
            .Options;
        return new IdentityDbContext(options);
    }

    private static IConfiguration Configuration(params (string Key, string Value)[] values) =>
        new ConfigurationBuilder()
            .AddInMemoryCollection(values.ToDictionary(item => item.Key, item => (string?)item.Value))
            .Build();
}
