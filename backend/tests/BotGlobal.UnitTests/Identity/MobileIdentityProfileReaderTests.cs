using BotGlobal.Contracts.Mobile;
using BotGlobal.Identity.Application;
using BotGlobal.Identity.Domain;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.UnitTests.Identity;

public sealed class MobileIdentityProfileReaderTests
{
    [Fact]
    public async Task ActiveMembershipReturnsCanonicalGlobalUserProfileWithoutInternalIds()
    {
        await using var db = CreateDb();
        var user = new ApplicationUser(Guid.NewGuid(), "canonical-user", "person@example.test", "Canonical Name");
        var membership = new ApplicationMembership(
            Guid.NewGuid(),
            BotGlobalApplications.Nqrb,
            $"user:{user.Id:N}",
            "Membership Snapshot",
            user.Id,
            false,
            DateTimeOffset.UtcNow);
        db.Users.Add(user);
        db.ApplicationMemberships.Add(membership);
        await db.SaveChangesAsync();

        var result = await new MobileIdentityProfileReader(db).ReadAsync(
            Descriptor(membership),
            CancellationToken.None);

        Assert.NotNull(result);
        Assert.Equal("Canonical Name", result.DisplayName);
        Assert.Equal("person@example.test", result.Email);
        Assert.DoesNotContain(user.Id.ToString(), result.ToString(), StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain(membership.Id.ToString(), result.ToString(), StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task DescriptorCannotReadAnotherMembershipProfile()
    {
        await using var db = CreateDb();
        var user = new ApplicationUser(Guid.NewGuid(), "first-user", "first@example.test", "First User");
        var membership = new ApplicationMembership(
            Guid.NewGuid(), BotGlobalApplications.Nqrb, $"user:{user.Id:N}", "First User",
            user.Id, false, DateTimeOffset.UtcNow);
        db.Users.Add(user);
        db.ApplicationMemberships.Add(membership);
        await db.SaveChangesAsync();

        var wrongIdentity = Descriptor(membership) with { MembershipId = Guid.NewGuid() };
        var result = await new MobileIdentityProfileReader(db).ReadAsync(wrongIdentity, CancellationToken.None);

        Assert.Null(result);
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
