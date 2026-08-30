using BotGlobal.Identity.Domain;

namespace BotGlobal.UnitTests.Identity;

public sealed class ApplicationMembershipTests
{
    [Fact]
    public void Guest_upgrade_preserves_membership_and_scopes_it_to_one_application()
    {
        var id = Guid.NewGuid();
        var membership = new ApplicationMembership(
            id,
            "family-games",
            "guest:temporary",
            "Guest",
            null,
            true,
            DateTimeOffset.UtcNow);
        var userId = Guid.NewGuid();

        membership.Upgrade(userId, $"user:{userId:N}", "Registered", DateTimeOffset.UtcNow);

        Assert.Equal(id, membership.Id);
        Assert.Equal("family-games", membership.ApplicationKey);
        Assert.Equal(userId, membership.GlobalUserId);
        Assert.False(membership.IsGuest);
    }

    [Fact]
    public void Membership_requires_explicit_application_scope()
    {
        Assert.Throws<ArgumentException>(() =>
            new ApplicationMembership(
                Guid.NewGuid(),
                " ",
                "guest:value",
                "Guest",
                null,
                true,
                DateTimeOffset.UtcNow));
    }
}
