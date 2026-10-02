using BotGlobal.Identity.Application;
using BotGlobal.Identity.Domain;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.UnitTests.Identity;

public sealed class CallingParticipantDirectoryTests
{
    [Fact]
    public async Task List_returns_active_same_application_memberships_without_current_member()
    {
        await using var db = CreateDbContext();
        var current = Membership("nqrb", "Current");
        var first = Membership("nqrb", "Alpha");
        var second = Membership("nqrb", "Beta");
        var inactive = Membership("nqrb", "Inactive");
        inactive.Deactivate();
        var otherApplication = Membership("family-games", "Other application");
        db.ApplicationMemberships.AddRange(
            current,
            second,
            inactive,
            otherApplication,
            first);
        await db.SaveChangesAsync();
        var directory = new CallingParticipantDirectory(db);

        var result = await directory.ListCallableAsync(
            "NQRB",
            current.Id,
            CancellationToken.None);

        Assert.Collection(
            result,
            participant =>
            {
                Assert.Equal(first.Id, participant.MembershipId);
                Assert.Equal(first.DisplayName, participant.DisplayName);
                Assert.Equal("nqrb", participant.ApplicationKey);
                Assert.True(participant.IsActive);
            },
            participant =>
            {
                Assert.Equal(second.Id, participant.MembershipId);
                Assert.Equal(second.DisplayName, participant.DisplayName);
                Assert.Equal("nqrb", participant.ApplicationKey);
                Assert.True(participant.IsActive);
            });
    }

    [Fact]
    public async Task List_returns_an_empty_result_when_no_remote_member_is_callable()
    {
        await using var db = CreateDbContext();
        var current = Membership("nqrb", "Current");
        db.ApplicationMemberships.Add(current);
        await db.SaveChangesAsync();
        var directory = new CallingParticipantDirectory(db);

        var result = await directory.ListCallableAsync(
            "nqrb",
            current.Id,
            CancellationToken.None);

        Assert.Empty(result);
    }

    [Fact]
    public async Task Find_returns_active_membership_with_matching_identity_and_display()
    {
        await using var db = CreateDbContext();
        var membership = Membership("nqrb", "Callable person");
        db.ApplicationMemberships.Add(membership);
        await db.SaveChangesAsync();
        var directory = new CallingParticipantDirectory(db);

        var result = await directory.FindAsync(
            "nqrb",
            membership.Id,
            CancellationToken.None);

        Assert.NotNull(result);
        Assert.Equal(membership.Id, result.MembershipId);
        Assert.Equal(membership.DisplayName, result.DisplayName);
        Assert.Equal(membership.SubjectId, result.SubjectId);
    }

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

    [Fact]
    public async Task Search_returns_only_active_non_guest_same_application_accounts_without_private_identifiers()
    {
        await using var db = CreateDbContext();
        var current = Membership("nqrb", "Alpha Current");
        var match = Membership("nqrb", "Alpha Contact");
        var inactive = Membership("nqrb", "Alpha Inactive");
        inactive.Deactivate();
        var guest = new ApplicationMembership(
            Guid.NewGuid(),
            "nqrb",
            $"subject-{Guid.NewGuid():N}",
            "Alpha Guest",
            null,
            true,
            DateTimeOffset.Parse("2026-08-31T12:00:00Z"));
        var foreign = Membership("family-games", "Alpha Foreign");
        db.ApplicationMemberships.AddRange(current, match, inactive, guest, foreign);
        await db.SaveChangesAsync();
        var directory = new CallingAccountDirectory(db);

        var result = await directory.SearchActiveNonGuestsAsync(
            "NQRB",
            current.Id,
            "Alpha",
            1,
            10,
            CancellationToken.None);

        var item = Assert.Single(result.Items);
        Assert.Equal(match.Id, item.MembershipId);
        Assert.Equal("Alpha Contact", item.DisplayName);
    }

    [Fact]
    public async Task Lookup_rejects_guest_inactive_and_foreign_memberships()
    {
        await using var db = CreateDbContext();
        var active = Membership("nqrb", "Active");
        var inactive = Membership("nqrb", "Inactive");
        inactive.Deactivate();
        var guest = new ApplicationMembership(
            Guid.NewGuid(),
            "nqrb",
            $"subject-{Guid.NewGuid():N}",
            "Guest",
            null,
            true,
            DateTimeOffset.Parse("2026-08-31T12:00:00Z"));
        var foreign = Membership("family-games", "Foreign");
        db.ApplicationMemberships.AddRange(active, inactive, guest, foreign);
        await db.SaveChangesAsync();
        var directory = new CallingAccountDirectory(db);

        Assert.NotNull(await directory.FindActiveNonGuestAsync("nqrb", active.Id, CancellationToken.None));
        Assert.Null(await directory.FindActiveNonGuestAsync("nqrb", inactive.Id, CancellationToken.None));
        Assert.Null(await directory.FindActiveNonGuestAsync("nqrb", guest.Id, CancellationToken.None));
        Assert.Null(await directory.FindActiveNonGuestAsync("nqrb", foreign.Id, CancellationToken.None));
    }

    private static IdentityDbContext CreateDbContext()
    {
        var options = new DbContextOptionsBuilder<IdentityDbContext>()
            .UseInMemoryDatabase($"calling-participants-{Guid.NewGuid():N}")
            .Options;
        return new IdentityDbContext(options);
    }

    private static ApplicationMembership Membership(
        string applicationKey,
        string displayName = "Participant") =>
        new(
            Guid.NewGuid(),
            applicationKey,
            $"subject-{Guid.NewGuid():N}",
            displayName,
            Guid.NewGuid(),
            false,
            DateTimeOffset.Parse("2026-08-31T12:00:00Z"));
}
