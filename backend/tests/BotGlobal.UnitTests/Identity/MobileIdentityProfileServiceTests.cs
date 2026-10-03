using BotGlobal.Contracts.Mobile;
using BotGlobal.Identity.Application;
using BotGlobal.Identity.Domain;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.UnitTests.Identity;

public sealed class MobileIdentityProfileServiceTests
{
    [Fact]
    public async Task UpdatesOnlyActiveMembershipOwnedByPrincipal()
    {
        await using var db = CreateDb();
        var userId = Guid.NewGuid();
        var membership = new ApplicationMembership(
            Guid.NewGuid(),
            BotGlobalApplications.FamilyGames,
            $"user:{userId:N}",
            "Before",
            userId,
            false,
            DateTimeOffset.UtcNow);
        var other = new ApplicationMembership(
            Guid.NewGuid(),
            BotGlobalApplications.Nqrb,
            $"user:{userId:N}",
            "Other App",
            userId,
            false,
            DateTimeOffset.UtcNow);
        db.ApplicationMemberships.AddRange(membership, other);
        await db.SaveChangesAsync();

        var result = await new MobileIdentityProfileService(db).UpdateAsync(
            Descriptor(membership),
            new MobileIdentityProfileUpdateRequest("  Player One  "),
            CancellationToken.None);

        Assert.True(result.Succeeded);
        Assert.Equal("Player One", result.Identity!.DisplayName);
        Assert.Equal("Player One", (await db.ApplicationMemberships.FindAsync(membership.Id))!.DisplayName);
        Assert.Equal("Other App", (await db.ApplicationMemberships.FindAsync(other.Id))!.DisplayName);
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    public async Task RejectsBlankName(string displayName)
    {
        await using var db = CreateDb();
        var membership = Membership();
        db.ApplicationMemberships.Add(membership);
        await db.SaveChangesAsync();

        var result = await new MobileIdentityProfileService(db).UpdateAsync(
            Descriptor(membership),
            new MobileIdentityProfileUpdateRequest(displayName),
            CancellationToken.None);

        Assert.False(result.Succeeded);
        Assert.Contains("display_name_required", result.Errors["displayName"]);
    }

    [Fact]
    public async Task RejectsGuestWrongApplicationAndInactiveMembership()
    {
        await using var db = CreateDb();
        var guest = new ApplicationMembership(
            Guid.NewGuid(), BotGlobalApplications.FamilyGames, "guest:test", "Guest", null, true, DateTimeOffset.UtcNow);
        var inactive = Membership();
        inactive.Deactivate();
        db.ApplicationMemberships.AddRange(guest, inactive);
        await db.SaveChangesAsync();
        var service = new MobileIdentityProfileService(db);

        Assert.False((await service.UpdateAsync(
            Descriptor(guest),
            new MobileIdentityProfileUpdateRequest("Name"),
            CancellationToken.None)).Succeeded);
        Assert.False((await service.UpdateAsync(
            Descriptor(inactive),
            new MobileIdentityProfileUpdateRequest("Name"),
            CancellationToken.None)).Succeeded);
        Assert.False((await service.UpdateAsync(
            Descriptor(inactive) with { ApplicationKey = BotGlobalApplications.Nqrb },
            new MobileIdentityProfileUpdateRequest("Name"),
            CancellationToken.None)).Succeeded);
    }

    private static ApplicationMembership Membership()
    {
        var userId = Guid.NewGuid();
        return new ApplicationMembership(
            Guid.NewGuid(),
            BotGlobalApplications.FamilyGames,
            $"user:{userId:N}",
            "Before",
            userId,
            false,
            DateTimeOffset.UtcNow);
    }

    private static ApplicationIdentityDescriptor Descriptor(ApplicationMembership membership) => new(
        membership.Id,
        membership.GlobalUserId,
        membership.SubjectId,
        membership.ApplicationKey,
        membership.DisplayName,
        membership.IsGuest);

    private static IdentityDbContext CreateDb() => new(
        new DbContextOptionsBuilder<IdentityDbContext>()
            .UseInMemoryDatabase(Guid.NewGuid().ToString("N"))
            .Options);
}
