using BotGlobal.Contracts.Mobile;
using BotGlobal.Identity.Application;
using BotGlobal.Identity.Domain;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Identity;

public sealed class MobileFederatedIdentityServiceTests
{
    [Fact]
    public async Task ProviderSubjectResolvesOneCentralUserWithIsolatedApplicationMemberships()
    {
        await using var db = CreateDb();
        await db.Database.EnsureCreatedAsync();
        using var users = CreateUserManager(db);
        var service = new MobileFederatedIdentityService(
            db,
            users,
            new FixedValidator(ValidatedIdentity()),
            new MobileApplicationTokenService(db, TimeProvider.System),
            TimeProvider.System);

        var nqrb = await service.AuthenticateAsync(
            BotGlobalApplications.Nqrb,
            new MobileFederatedIdentityRequest("google", "first-transient-token"),
            CancellationToken.None);
        var games = await service.AuthenticateAsync(
            BotGlobalApplications.FamilyGames,
            new MobileFederatedIdentityRequest("google", "second-transient-token"),
            CancellationToken.None);

        Assert.True(nqrb.Succeeded);
        Assert.True(games.Succeeded);
        Assert.Equal(BotGlobalApplications.Nqrb, nqrb.Session!.Identity.ApplicationKey);
        Assert.Equal(BotGlobalApplications.FamilyGames, games.Session!.Identity.ApplicationKey);
        Assert.NotEqual(nqrb.Session.Identity.MembershipId, games.Session.Identity.MembershipId);
        Assert.Single(await db.Users.ToListAsync());
        Assert.Equal(2, await db.ApplicationMemberships.CountAsync());
        Assert.NotNull(await users.FindByLoginAsync("google", "google-subject-123"));
    }

    [Fact]
    public async Task MatchingEmailWithoutProviderLinkRequiresExplicitAccountLinking()
    {
        await using var db = CreateDb();
        await db.Database.EnsureCreatedAsync();
        using var users = CreateUserManager(db);
        var existing = new ApplicationUser(Guid.NewGuid(), "existing", "person@example.test", "Existing");
        Assert.True((await users.CreateAsync(existing)).Succeeded);
        var service = new MobileFederatedIdentityService(
            db,
            users,
            new FixedValidator(ValidatedIdentity()),
            new MobileApplicationTokenService(db, TimeProvider.System),
            TimeProvider.System);

        var result = await service.AuthenticateAsync(
            BotGlobalApplications.Nqrb,
            new MobileFederatedIdentityRequest("google", "transient-token"),
            CancellationToken.None);

        Assert.False(result.Succeeded);
        Assert.Contains("account_link_required", result.Errors["accountLink"]);
        Assert.Single(await db.Users.ToListAsync());
        Assert.Empty(await db.ApplicationMemberships.ToListAsync());
    }

    private static IdentityDbContext CreateDb() => new(
        new DbContextOptionsBuilder<IdentityDbContext>()
            .UseInMemoryDatabase(Guid.NewGuid().ToString("N"))
            .Options);

    private static UserManager<ApplicationUser> CreateUserManager(IdentityDbContext db)
    {
        var store = new Microsoft.AspNetCore.Identity.EntityFrameworkCore.UserStore<
            ApplicationUser,
            IdentityRole<Guid>,
            IdentityDbContext,
            Guid>(db);
        var options = Options.Create(new IdentityOptions { User = { RequireUniqueEmail = true } });
        return new UserManager<ApplicationUser>(
            store,
            options,
            new PasswordHasher<ApplicationUser>(),
            [new UserValidator<ApplicationUser>()],
            [new PasswordValidator<ApplicationUser>()],
            new UpperInvariantLookupNormalizer(),
            new IdentityErrorDescriber(),
            new ServiceCollection().BuildServiceProvider(),
            NullLogger<UserManager<ApplicationUser>>.Instance);
    }

    private static ValidatedFederatedIdentity ValidatedIdentity() =>
        new("google", "google-subject-123", "person@example.test", "Person");

    private sealed class FixedValidator(ValidatedFederatedIdentity identity) : IFederatedIdentityTokenValidator
    {
        public Task<FederatedIdentityValidationResult> ValidateAsync(
            string provider,
            string idToken,
            CancellationToken cancellationToken) =>
            Task.FromResult(FederatedIdentityValidationResult.Success(identity));
    }
}
