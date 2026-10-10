using BotGlobal.Contracts.Communication;
using BotGlobal.Contracts.Notifications;
using BotGlobal.Identity.Application;
using BotGlobal.Identity.Domain;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace BotGlobal.UnitTests.Identity;

public sealed class PresenceSessionAdapterTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-10-09T13:00:00Z");

    [Fact]
    public async Task Connection_context_cannot_substitute_another_session_or_application()
    {
        await using var db = CreateDb();
        var fixture = AddSession(db, "nqrb", "access-a");
        var other = AddSession(db, "other-app", "access-b");
        await db.SaveChangesAsync();
        var adapter = Adapter(db, "nqrb");

        var current = await adapter.ValidateConnectionAsync(Credential(fixture), default);
        var substituted = await adapter.ValidateConnectionAsync(Credential(other), default);

        Assert.NotNull(current);
        Assert.Equal(fixture.Membership.Id, current.Authority.MembershipId);
        Assert.Null(substituted);
    }

    [Theory]
    [InlineData("expired")]
    [InlineData("revoked")]
    [InlineData("deleted")]
    [InlineData("inactive")]
    [InlineData("guest")]
    public async Task Expired_revoked_deleted_or_inactive_authority_is_rejected(string state)
    {
        await using var db = CreateDb();
        var fixture = AddSession(
            db,
            "nqrb",
            "access",
            expiresAt: state == "expired" ? Now.AddSeconds(-1) : Now.AddMinutes(5),
            isGuest: state == "guest");
        if (state == "revoked") fixture.Session.Revoke(Now);
        if (state == "inactive") fixture.Membership.Deactivate();
        if (state == "deleted") db.ChangeTracker.Clear();
        else await db.SaveChangesAsync();

        var current = await Adapter(db, "nqrb").ValidateConnectionAsync(Credential(fixture), default);

        Assert.Null(current);
    }

    [Fact]
    public async Task Credential_rotation_keeps_session_id_but_invalidates_the_old_revision()
    {
        await using var db = CreateDb();
        var fixture = AddSession(db, "nqrb", "before");
        await db.SaveChangesAsync();
        var adapter = Adapter(db, "nqrb");
        var beforeCredential = Credential(fixture);
        var before = (await adapter.ValidateConnectionAsync(beforeCredential, default))!.Authority;

        fixture.Session.Rotate(Hash("after"), Hash("refresh-after"), Now.AddMinutes(10), Now.AddDays(1));
        await db.SaveChangesAsync();

        Assert.False(await adapter.RevalidateAsync(before, default));
        Assert.Null(await adapter.ValidateConnectionAsync(beforeCredential, default));
        var after = (await adapter.ValidateConnectionAsync(Credential(fixture), default))!.Authority;
        Assert.Equal(before.SessionId, after.SessionId);
        Assert.NotEqual(before.CredentialRevision, after.CredentialRevision);
    }

    private static PresenceSessionAdapter Adapter(IdentityDbContext db, string applicationKey) => new(
        db,
        new Applications(applicationKey),
        new ServiceCollection().BuildServiceProvider(),
        new FixedClock());

    private static (ApplicationMembership Membership, MobileApplicationSession Session) AddSession(
        IdentityDbContext db,
        string applicationKey,
        string token,
        DateTimeOffset? expiresAt = null,
        bool isGuest = false)
    {
        var membership = new ApplicationMembership(
            Guid.NewGuid(), applicationKey, $"subject:{token}", "Person", isGuest ? null : Guid.NewGuid(), isGuest, Now);
        var session = new MobileApplicationSession(
            Guid.NewGuid(), membership.Id, Hash(token), Hash("refresh-" + token),
            expiresAt ?? Now.AddMinutes(5), Now.AddDays(1), Now);
        db.ApplicationMemberships.Add(membership);
        db.MobileApplicationSessions.Add(session);
        return (membership, session);
    }

    private static byte[] Hash(string value) => System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(value));
    private static PresenceConnectionCredential Credential(
        (ApplicationMembership Membership, MobileApplicationSession Session) fixture) =>
        new(
            fixture.Session.Id,
            fixture.Membership.Id,
            fixture.Membership.ApplicationKey,
            Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(fixture.Session.AccessTokenHash)),
            (_, _) => ValueTask.FromResult(true));
    private static IdentityDbContext CreateDb() => new(
        new DbContextOptionsBuilder<IdentityDbContext>()
            .UseInMemoryDatabase(Guid.NewGuid().ToString("N"))
            .Options);

    private sealed class FixedClock : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => Now;
    }

    private sealed class Applications(string activeKey) : IPlatformClientApplicationResolver
    {
        public Task<PlatformClientDescriptor?> FindByClientKeyAsync(string clientKey, CancellationToken cancellationToken) =>
            Task.FromResult<PlatformClientDescriptor?>(clientKey == activeKey
                ? new(Guid.Parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), clientKey, "Application", true)
                : null);
    }
}
