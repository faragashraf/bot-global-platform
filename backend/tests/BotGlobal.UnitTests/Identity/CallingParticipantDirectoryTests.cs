using BotGlobal.Identity.Application;
using BotGlobal.Identity.Domain;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.UnitTests.Identity;

public sealed class CallingParticipantDirectoryTests
{
    [Fact]
    public async Task Find_returns_null_for_an_inactive_membership()
    {
        await using var db = CreateDbContext();
        var membership = Membership("test-app");
        membership.Deactivate();
        db.ApplicationMemberships.Add(membership);
        await db.SaveChangesAsync();
        var directory = new CallingParticipantDirectory(db);

        var result = await directory.FindAsync("test-app", membership.Id, CancellationToken.None);

        Assert.Null(result);
    }

    [Fact]
    public async Task Find_returns_null_for_a_membership_in_another_application()
    {
        await using var db = CreateDbContext();
        var membership = Membership("product-blue");
        db.ApplicationMemberships.Add(membership);
        await db.SaveChangesAsync();
        var directory = new CallingParticipantDirectory(db);

        var result = await directory.FindAsync("product-green", membership.Id, CancellationToken.None);

        Assert.Null(result);
    }

    private static IdentityDbContext CreateDbContext()
    {
        var options = new DbContextOptionsBuilder<IdentityDbContext>()
            .UseInMemoryDatabase($"calling-participants-{Guid.NewGuid():N}")
            .Options;
        return new IdentityDbContext(options);
    }

    private static ApplicationMembership Membership(string applicationKey) =>
        new(
            Guid.NewGuid(),
            applicationKey,
            $"subject-{Guid.NewGuid():N}",
            "Participant",
            Guid.NewGuid(),
            false,
            DateTimeOffset.Parse("2026-08-31T12:00:00Z"));
}
