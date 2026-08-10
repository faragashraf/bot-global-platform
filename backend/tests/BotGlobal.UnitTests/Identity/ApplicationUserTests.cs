using BotGlobal.Identity.Domain;

namespace BotGlobal.UnitTests.Identity;

public sealed class ApplicationUserTests
{
    [Fact]
    public void Constructor_RejectsEmptyId()
    {
        Assert.Throws<ArgumentException>(
            () =>
                new ApplicationUser(
                    Guid.Empty,
                    "admin",
                    "admin@example.test",
                    "Administrator"));
    }

    [Fact]
    public void Constructor_RequiresDisplayName()
    {
        Assert.Throws<ArgumentException>(
            () =>
                new ApplicationUser(
                    Guid.NewGuid(),
                    "admin",
                    "admin@example.test",
                    " "));
    }

    [Fact]
    public void User_CanBeDeactivatedAndActivated()
    {
        var user =
            new ApplicationUser(
                Guid.NewGuid(),
                "admin",
                "admin@example.test",
                "Administrator");

        user.Deactivate();

        Assert.False(user.IsActive);

        user.Activate();

        Assert.True(user.IsActive);
    }
}
