using System.Security.Claims;
using BotGlobal.Calling.Application;
using BotGlobal.Calling.Domain;
using BotGlobal.Calling.Infrastructure;
using BotGlobal.Calling.Realtime;
using BotGlobal.Contracts.Calling;
using BotGlobal.Contracts.Mobile;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;

namespace BotGlobal.UnitTests.Calling;

public sealed class NqrbContactBookServiceTests
{
    [Fact]
    public async Task Contact_add_is_one_way_idempotent_and_remove_affects_only_owner()
    {
        await using var db = CreateDb();
        var owner = Guid.NewGuid();
        var contact = Guid.NewGuid();
        var otherOwner = Guid.NewGuid();
        var directory = new FixedAccountDirectory(
            new CallingAccountDescriptor(contact, "Saved Person"));
        var service = new NqrbContactBookService(db, directory, TimeProvider.System);

        Assert.Empty((await service.ListAsync(owner, 1, 20, CancellationToken.None)).Items);
        var added = await service.AddAsync(owner, contact, CancellationToken.None);
        var repeated = await service.AddAsync(owner, contact, CancellationToken.None);

        Assert.Equal(NqrbContactAddStatus.Added, added.Status);
        Assert.Equal(NqrbContactAddStatus.Existing, repeated.Status);
        Assert.Equal(contact, Assert.Single((await service.ListAsync(owner, 1, 20, CancellationToken.None)).Items).MembershipId);
        Assert.Empty((await service.ListAsync(otherOwner, 1, 20, CancellationToken.None)).Items);
        Assert.Single(await db.NqrbContactEdges.ToListAsync());

        await service.RemoveAsync(otherOwner, contact, CancellationToken.None);
        Assert.Single(await db.NqrbContactEdges.ToListAsync());

        await service.RemoveAsync(owner, contact, CancellationToken.None);
        Assert.Empty(await db.NqrbContactEdges.ToListAsync());
    }

    [Fact]
    public async Task Nickname_is_private_to_the_owner_and_clearing_restores_the_public_name()
    {
        await using var db = CreateDb();
        var owner = Guid.NewGuid();
        var otherOwner = Guid.NewGuid();
        var contact = Guid.NewGuid();
        var service = new NqrbContactBookService(db,
            new FixedAccountDirectory(new CallingAccountDescriptor(contact, "Public Name")), TimeProvider.System);
        await service.AddAsync(owner, contact, CancellationToken.None);
        await service.AddAsync(otherOwner, contact, CancellationToken.None);

        var renamed = await service.UpdateNicknameAsync(owner, contact, "  صديقي  ", CancellationToken.None);

        Assert.Equal(NqrbContactNicknameStatus.Updated, renamed.Status);
        Assert.Equal("صديقي", renamed.Contact?.Nickname);
        Assert.Equal("صديقي", Assert.Single((await service.ListAsync(owner, 1, 20, CancellationToken.None)).Items).Nickname);
        Assert.Null(Assert.Single((await service.ListAsync(otherOwner, 1, 20, CancellationToken.None)).Items).Nickname);
        Assert.Equal("صديقي", (await service.FindAsync(owner, contact, CancellationToken.None))?.Nickname);
        Assert.Null((await service.FindAsync(otherOwner, contact, CancellationToken.None))?.Nickname);
        Assert.Null(await service.FindAsync(Guid.NewGuid(), contact, CancellationToken.None));
        Assert.Equal("Public Name", Assert.Single(await service.ListCallableContactsAsync(owner, CancellationToken.None)).DisplayName);
        Assert.Equal("صديقي", (await service.AddAsync(owner, contact, CancellationToken.None)).Contact?.Nickname);

        var cleared = await service.UpdateNicknameAsync(owner, contact, "  ", CancellationToken.None);
        Assert.Equal(NqrbContactNicknameStatus.Updated, cleared.Status);
        Assert.Null(cleared.Contact?.Nickname);
        Assert.Equal("Public Name", cleared.Contact?.DisplayName);
    }

    [Fact]
    public async Task Nickname_rejects_invalid_input_and_unsaved_contacts_without_changes()
    {
        await using var db = CreateDb();
        var owner = Guid.NewGuid();
        var contact = Guid.NewGuid();
        var service = new NqrbContactBookService(db,
            new FixedAccountDirectory(new CallingAccountDescriptor(contact, "Public Name")), TimeProvider.System);

        Assert.Equal(NqrbContactNicknameStatus.Unavailable,
            (await service.UpdateNicknameAsync(owner, contact, "Friend", CancellationToken.None)).Status);
        await service.AddAsync(owner, contact, CancellationToken.None);
        Assert.Equal(NqrbContactNicknameStatus.Invalid,
            (await service.UpdateNicknameAsync(owner, contact, new string('x', 81), CancellationToken.None)).Status);
        Assert.Equal(NqrbContactNicknameStatus.Invalid,
            (await service.UpdateNicknameAsync(owner, contact, "line\nbreak", CancellationToken.None)).Status);
        Assert.Null(Assert.Single((await service.ListAsync(owner, 1, 20, CancellationToken.None)).Items).Nickname);
    }

    [Fact]
    public async Task Add_rejects_self_and_unavailable_targets_without_persisting_edge()
    {
        await using var db = CreateDb();
        var owner = Guid.NewGuid();
        var service = new NqrbContactBookService(
            db,
            new FixedAccountDirectory(),
            TimeProvider.System);

        var self = await service.AddAsync(owner, owner, CancellationToken.None);
        var missing = await service.AddAsync(owner, Guid.NewGuid(), CancellationToken.None);

        Assert.Equal(NqrbContactAddStatus.Unavailable, self.Status);
        Assert.Equal(NqrbContactAddStatus.Unavailable, missing.Status);
        Assert.Empty(await db.NqrbContactEdges.ToListAsync());
    }

    [Fact]
    public async Task Contact_pages_skip_inactive_accounts_before_page_slicing()
    {
        await using var fixture = await SqliteCallingFixture.CreateAsync();
        var db = fixture.Db;
        var owner = Guid.NewGuid();
        var stale = Guid.Parse("00000000-0000-0000-0000-000000000001");
        var first = Guid.Parse("00000000-0000-0000-0000-000000000002");
        var second = Guid.Parse("00000000-0000-0000-0000-000000000003");
        var now = DateTimeOffset.UtcNow;
        db.NqrbContactEdges.AddRange(
            new NqrbContactEdge(BotGlobalApplications.Nqrb, owner, stale, now),
            new NqrbContactEdge(BotGlobalApplications.Nqrb, owner, first, now.AddSeconds(1)),
            new NqrbContactEdge(BotGlobalApplications.Nqrb, owner, second, now.AddSeconds(2)));
        await db.SaveChangesAsync();
        var service = new NqrbContactBookService(
            db,
            new FixedAccountDirectory(
                new CallingAccountDescriptor(owner, "Owner"),
                new CallingAccountDescriptor(first, "First"),
                new CallingAccountDescriptor(second, "Second")),
            TimeProvider.System);

        var firstPage = await service.ListAsync(owner, 1, 1, CancellationToken.None);
        var secondPage = await service.ListAsync(owner, 2, 1, CancellationToken.None);

        Assert.Equal(first, Assert.Single(firstPage.Items).MembershipId);
        Assert.True(firstPage.HasMore);
        Assert.Equal(second, Assert.Single(secondPage.Items).MembershipId);
        Assert.False(secondPage.HasMore);
    }

    [Fact]
    public async Task Saved_contact_lookup_tracks_one_way_add_and_remove()
    {
        await using var db = CreateDb();
        var caller = Guid.NewGuid();
        var callee = Guid.NewGuid();
        var accounts = new FixedAccountDirectory(
            new CallingAccountDescriptor(caller, "Caller"),
            new CallingAccountDescriptor(callee, "Callee", "callee-subject"));
        var contacts = new NqrbContactBookService(db, accounts, TimeProvider.System);
        Assert.Empty(await contacts.ListCallableContactsAsync(caller, CancellationToken.None));

        Assert.Equal(NqrbContactAddStatus.Added,
            (await contacts.AddAsync(caller, callee, CancellationToken.None)).Status);
        var callable = Assert.Single(await contacts.ListCallableContactsAsync(caller, CancellationToken.None));
        Assert.Equal(callee, callable.MembershipId);
        Assert.Equal("callee-subject", callable.SubjectId);

        await contacts.RemoveAsync(caller, callee, CancellationToken.None);
        Assert.Empty(await contacts.ListCallableContactsAsync(caller, CancellationToken.None));
    }

    [Fact]
    public async Task Add_from_call_history_uses_authorized_nqrb_counterpart_and_is_idempotent()
    {
        await using var db = CreateDb();
        var owner = Guid.NewGuid();
        var counterpart = Guid.NewGuid();
        var call = new CallRecord(Guid.NewGuid(), Guid.NewGuid(), BotGlobalApplications.Nqrb, DateTimeOffset.UtcNow);
        call.Participants.Add(new CallParticipantRecord(call.Id, owner, CallParticipantRole.Initiator, "Owner"));
        call.Participants.Add(new CallParticipantRecord(call.Id, counterpart, CallParticipantRole.Recipient, "Counterpart"));
        db.Calls.Add(call);
        await db.SaveChangesAsync();
        var service = new NqrbContactBookService(
            db,
            new FixedAccountDirectory(new CallingAccountDescriptor(counterpart, "Counterpart")),
            TimeProvider.System);

        var added = await service.AddFromCallHistoryAsync(owner, call.Id, CancellationToken.None);
        var repeated = await service.AddFromCallHistoryAsync(owner, call.Id, CancellationToken.None);

        Assert.Equal(NqrbContactAddStatus.Added, added.Status);
        Assert.Equal(counterpart, added.Contact?.MembershipId);
        Assert.Equal(NqrbContactAddStatus.Existing, repeated.Status);
        var edge = Assert.Single(await db.NqrbContactEdges.ToListAsync());
        Assert.Equal(owner, edge.OwnerMembershipId);
        Assert.Equal(counterpart, edge.ContactMembershipId);
    }

    [Fact]
    public async Task Add_from_call_history_rejects_other_apps_nonparticipants_and_guests()
    {
        await using var db = CreateDb();
        var owner = Guid.NewGuid();
        var counterpart = Guid.NewGuid();
        var other = Guid.NewGuid();
        var otherAppCall = new CallRecord(Guid.NewGuid(), Guid.NewGuid(), "family-games", DateTimeOffset.UtcNow);
        otherAppCall.Participants.Add(new CallParticipantRecord(otherAppCall.Id, owner, CallParticipantRole.Initiator, "Owner"));
        otherAppCall.Participants.Add(new CallParticipantRecord(otherAppCall.Id, counterpart, CallParticipantRole.Recipient, "Counterpart"));
        var nqrbCall = new CallRecord(Guid.NewGuid(), Guid.NewGuid(), BotGlobalApplications.Nqrb, DateTimeOffset.UtcNow);
        nqrbCall.Participants.Add(new CallParticipantRecord(nqrbCall.Id, other, CallParticipantRole.Initiator, "Other"));
        nqrbCall.Participants.Add(new CallParticipantRecord(nqrbCall.Id, counterpart, CallParticipantRole.Recipient, "Counterpart"));
        var guestCall = new CallRecord(Guid.NewGuid(), Guid.NewGuid(), BotGlobalApplications.Nqrb, DateTimeOffset.UtcNow, isGuestCall: true);
        guestCall.Participants.Add(new CallParticipantRecord(guestCall.Id, owner, CallParticipantRole.Recipient, "Owner"));
        guestCall.Participants.Add(new CallParticipantRecord(guestCall.Id, counterpart, CallParticipantRole.Initiator, "Guest"));
        db.Calls.AddRange(otherAppCall, nqrbCall, guestCall);
        await db.SaveChangesAsync();
        var service = new NqrbContactBookService(
            db,
            new FixedAccountDirectory(new CallingAccountDescriptor(counterpart, "Counterpart")),
            TimeProvider.System);

        Assert.Equal(NqrbContactAddStatus.Unavailable,
            (await service.AddFromCallHistoryAsync(owner, otherAppCall.Id, CancellationToken.None)).Status);
        Assert.Equal(NqrbContactAddStatus.Unavailable,
            (await service.AddFromCallHistoryAsync(owner, nqrbCall.Id, CancellationToken.None)).Status);
        Assert.Equal(NqrbContactAddStatus.Unavailable,
            (await service.AddFromCallHistoryAsync(owner, guestCall.Id, CancellationToken.None)).Status);
        Assert.Empty(await db.NqrbContactEdges.ToListAsync());
    }

    [Fact]
    public async Task Account_deletion_removes_edges_where_deleted_account_is_owner_or_contact()
    {
        await using var db = CreateDb();
        var deleted = Guid.NewGuid();
        var first = Guid.NewGuid();
        var second = Guid.NewGuid();
        var third = Guid.NewGuid();
        db.NqrbContactEdges.AddRange(
            new NqrbContactEdge("nqrb", deleted, first, DateTimeOffset.UtcNow),
            new NqrbContactEdge("nqrb", first, deleted, DateTimeOffset.UtcNow),
            new NqrbContactEdge("nqrb", second, third, DateTimeOffset.UtcNow),
            new NqrbContactEdge("family-games", first, deleted, DateTimeOffset.UtcNow));
        db.NqrbContactInvites.AddRange(
            new NqrbContactInvite("nqrb", "a".PadLeft(64, 'a'), deleted, DateTimeOffset.UtcNow, DateTimeOffset.UtcNow.AddHours(1)),
            new NqrbContactInvite("nqrb", "b".PadLeft(64, 'b'), first, DateTimeOffset.UtcNow, DateTimeOffset.UtcNow.AddHours(1)),
            new NqrbContactInvite("family-games", "c".PadLeft(64, 'c'), deleted, DateTimeOffset.UtcNow, DateTimeOffset.UtcNow.AddHours(1)));
        await db.SaveChangesAsync();

        await new CallingAccountDataEraser(db).DeleteAsync(
            "nqrb",
            deleted,
            CancellationToken.None);

        var remaining = await db.NqrbContactEdges
            .OrderBy(item => item.ApplicationKey)
            .ThenBy(item => item.OwnerMembershipId)
            .ToListAsync();
        Assert.Equal(2, remaining.Count);
        Assert.Contains(remaining, item => item.ApplicationKey == "nqrb" && item.OwnerMembershipId == second && item.ContactMembershipId == third);
        Assert.Contains(remaining, item => item.ApplicationKey == "family-games");
        var remainingInvites = await db.NqrbContactInvites
            .OrderBy(item => item.ApplicationKey)
            .ThenBy(item => item.CodeHash)
            .ToListAsync();
        Assert.Equal(2, remainingInvites.Count);
        Assert.Contains(remainingInvites, item => item.ApplicationKey == "nqrb" && item.IssuerMembershipId == first);
        Assert.Contains(remainingInvites, item => item.ApplicationKey == "family-games");
    }

    [Fact]
    public async Task Invite_acceptance_previews_issuer_only_and_creates_mutual_edges_once()
    {
        await using var fixture = await SqliteCallingFixture.CreateAsync();
        var issuer = Guid.NewGuid();
        var recipient = Guid.NewGuid();
        var service = new NqrbContactBookService(
            fixture.Db,
            new FixedAccountDirectory(
                new CallingAccountDescriptor(issuer, "Issuer Name"),
                new CallingAccountDescriptor(recipient, "Recipient Name")),
            TimeProvider.System);

        var created = await service.CreateInviteAsync(issuer, CancellationToken.None);
        var code = AssertInvite(created);
        var stored = Assert.Single(await fixture.Db.NqrbContactInvites.ToListAsync());

        Assert.DoesNotContain(code.Replace("-", string.Empty), stored.CodeHash, StringComparison.OrdinalIgnoreCase);
        Assert.Equal(64, stored.CodeHash.Length);

        var preview = await service.PreviewInviteAsync(recipient, code, CancellationToken.None);
        Assert.Equal(NqrbContactInviteStatus.Created, preview.Status);
        Assert.Equal("Issuer Name", preview.Preview?.IssuerDisplayName);

        var accepted = await service.AcceptInviteAsync(recipient, code, CancellationToken.None);
        var replay = await service.AcceptInviteAsync(recipient, code, CancellationToken.None);

        Assert.Equal(NqrbContactInviteStatus.Accepted, accepted.Status);
        Assert.Equal(issuer, accepted.Issuer?.MembershipId);
        Assert.Equal(NqrbContactInviteStatus.AlreadyClaimed, replay.Status);
        var edges = await fixture.Db.NqrbContactEdges.ToListAsync();
        Assert.Equal(2, edges.Count);
        Assert.Contains(edges, item => item.OwnerMembershipId == issuer && item.ContactMembershipId == recipient);
        Assert.Contains(edges, item => item.OwnerMembershipId == recipient && item.ContactMembershipId == issuer);
    }

    [Fact]
    public async Task Invite_acceptance_completes_mutual_contacts_when_one_direction_already_exists()
    {
        await using var fixture = await SqliteCallingFixture.CreateAsync();
        var issuer = Guid.NewGuid();
        var recipient = Guid.NewGuid();
        var service = new NqrbContactBookService(
            fixture.Db,
            new FixedAccountDirectory(
                new CallingAccountDescriptor(issuer, "Issuer"),
                new CallingAccountDescriptor(recipient, "Recipient")),
            TimeProvider.System);
        Assert.Equal(NqrbContactAddStatus.Added,
            (await service.AddAsync(issuer, recipient, CancellationToken.None)).Status);
        var code = AssertInvite(await service.CreateInviteAsync(issuer, CancellationToken.None));

        var accepted = await service.AcceptInviteAsync(recipient, code, CancellationToken.None);

        Assert.Equal(NqrbContactInviteStatus.Accepted, accepted.Status);
        var edges = await fixture.Db.NqrbContactEdges.ToListAsync();
        Assert.Equal(2, edges.Count);
        Assert.Contains(edges, item => item.OwnerMembershipId == recipient && item.ContactMembershipId == issuer);
    }

    [Fact]
    public async Task Invite_expiry_self_missing_account_and_malformed_codes_fail_without_edges()
    {
        await using var fixture = await SqliteCallingFixture.CreateAsync();
        var issuer = Guid.NewGuid();
        var recipient = Guid.NewGuid();
        var clock = new MutableTimeProvider(DateTimeOffset.UtcNow);
        var service = new NqrbContactBookService(
            fixture.Db,
            new FixedAccountDirectory(new CallingAccountDescriptor(issuer, "Issuer")),
            clock);
        var created = await service.CreateInviteAsync(issuer, CancellationToken.None);
        var code = AssertInvite(created);

        Assert.Equal(
            NqrbContactInviteStatus.SelfInvite,
            (await service.AcceptInviteAsync(issuer, code, CancellationToken.None)).Status);
        Assert.Equal(
            NqrbContactInviteStatus.Unavailable,
            (await service.PreviewInviteAsync(recipient, code, CancellationToken.None)).Status);
        Assert.Equal(
            NqrbContactInviteStatus.Unavailable,
            (await service.AcceptInviteAsync(recipient, code, CancellationToken.None)).Status);
        Assert.Equal(
            NqrbContactInviteStatus.Invalid,
            (await service.AcceptInviteAsync(recipient, "bad-code", CancellationToken.None)).Status);
        Assert.Empty(await fixture.Db.NqrbContactEdges.ToListAsync());

        clock.Advance(TimeSpan.FromHours(25));
        var expiredService = new NqrbContactBookService(
            fixture.Db,
            new FixedAccountDirectory(
                new CallingAccountDescriptor(issuer, "Issuer"),
                new CallingAccountDescriptor(recipient, "Recipient")),
            clock);

        Assert.Equal(
            NqrbContactInviteStatus.Expired,
            (await expiredService.PreviewInviteAsync(recipient, code, CancellationToken.None)).Status);
        Assert.Equal(
            NqrbContactInviteStatus.Expired,
            (await expiredService.AcceptInviteAsync(recipient, code, CancellationToken.None)).Status);
        Assert.Empty(await fixture.Db.NqrbContactEdges.ToListAsync());
    }

    [Fact]
    public async Task Concurrent_invite_acceptance_allows_single_claim_without_duplicate_edges()
    {
        var database = Path.Combine(Path.GetTempPath(), $"nqrb-invite-race-{Guid.NewGuid():N}.db");
        try
        {
            var options = new DbContextOptionsBuilder<CallingDbContext>()
                .UseSqlite($"Data Source={database}")
                .Options;
            await using (var setup = new CallingDbContext(options))
            {
                await setup.Database.EnsureCreatedAsync();
                var issuer = Guid.NewGuid();
                var recipient = Guid.NewGuid();
                var created = await new NqrbContactBookService(
                    setup,
                    new FixedAccountDirectory(new CallingAccountDescriptor(issuer, "Issuer")),
                    TimeProvider.System).CreateInviteAsync(issuer, CancellationToken.None);
                var code = AssertInvite(created);

                async Task<NqrbContactInviteStatus> AcceptAsync()
                {
                    await using var db = new CallingDbContext(options);
                    var result = await new NqrbContactBookService(
                        db,
                        new FixedAccountDirectory(
                            new CallingAccountDescriptor(issuer, "Issuer"),
                            new CallingAccountDescriptor(recipient, "Recipient")),
                        TimeProvider.System).AcceptInviteAsync(recipient, code, CancellationToken.None);
                    return result.Status;
                }

                var results = await Task.WhenAll(AcceptAsync(), AcceptAsync());
                Assert.Equal(1, results.Count(item => item == NqrbContactInviteStatus.Accepted));
                Assert.Equal(1, results.Count(item => item == NqrbContactInviteStatus.AlreadyClaimed));
            }

            await using var verify = new CallingDbContext(options);
            Assert.Equal(2, await verify.NqrbContactEdges.CountAsync());
            Assert.Equal(1, await verify.NqrbContactInvites.CountAsync(item => item.ClaimedAtUtc != null));
        }
        finally
        {
            if (File.Exists(database))
            {
                File.Delete(database);
            }
        }
    }

    private static CallingDbContext CreateDb() => new(
        new DbContextOptionsBuilder<CallingDbContext>()
            .UseInMemoryDatabase($"nqrb-contacts-{Guid.NewGuid():N}")
            .Options);

    private static string AssertInvite(NqrbContactInviteCreateResult result)
    {
        Assert.Equal(NqrbContactInviteStatus.Created, result.Status);
        var invite = Assert.IsType<NqrbContactInviteCode>(result.Invite);
        Assert.StartsWith("NQ-", invite.Code);
        Assert.StartsWith("nqrb://invite/", invite.ShareLink);
        return invite.Code;
    }

    private sealed class SqliteCallingFixture : IAsyncDisposable
    {
        private readonly SqliteConnection connection = new("Data Source=:memory:");

        private SqliteCallingFixture() { }

        public CallingDbContext Db { get; private set; } = null!;

        public static async Task<SqliteCallingFixture> CreateAsync()
        {
            var fixture = new SqliteCallingFixture();
            await fixture.connection.OpenAsync();
            fixture.Db = new CallingDbContext(new DbContextOptionsBuilder<CallingDbContext>()
                .UseSqlite(fixture.connection)
                .Options);
            await fixture.Db.Database.EnsureCreatedAsync();
            return fixture;
        }

        public async ValueTask DisposeAsync()
        {
            await Db.DisposeAsync();
            await connection.DisposeAsync();
        }
    }

    private sealed class MutableTimeProvider(DateTimeOffset utcNow) : TimeProvider
    {
        private DateTimeOffset utcNow = utcNow;
        public override DateTimeOffset GetUtcNow() => utcNow;
        public void Advance(TimeSpan value) => utcNow = utcNow.Add(value);
    }

    private sealed class FixedAccountDirectory(params CallingAccountDescriptor[] accounts)
        : ICallingAccountDirectory
    {
        public Task<CallingAccountDescriptor?> FindActiveNonGuestAsync(
            string applicationKey,
            Guid membershipId,
            CancellationToken cancellationToken) =>
            Task.FromResult(accounts.SingleOrDefault(item => item.MembershipId == membershipId));

        public Task<IReadOnlyList<CallingAccountDescriptor>> FindActiveNonGuestAsync(
            string applicationKey,
            IReadOnlyCollection<Guid> membershipIds,
            CancellationToken cancellationToken) =>
            Task.FromResult<IReadOnlyList<CallingAccountDescriptor>>(
                accounts.Where(item => membershipIds.Contains(item.MembershipId)).ToArray());

        public Task<CallingAccountSearchPage> SearchActiveNonGuestsAsync(
            string applicationKey,
            Guid currentMembershipId,
            string query,
            int page,
            int pageSize,
            CancellationToken cancellationToken) =>
            Task.FromResult(new CallingAccountSearchPage(
                accounts.Where(item => item.MembershipId != currentMembershipId && item.DisplayName.Contains(query)).ToArray(),
                page,
                pageSize,
                false));
    }

    private sealed class PermissiveParticipantDirectory(Guid callee) : ICallingParticipantDirectory
    {
        public int FindCalls { get; private set; }
        public Task<IReadOnlyList<CallingParticipantDescriptor>> ListCallableAsync(
            string applicationKey, Guid currentMembershipId, CancellationToken cancellationToken) =>
            Task.FromResult<IReadOnlyList<CallingParticipantDescriptor>>([]);
        public Task<CallingParticipantDescriptor?> FindAsync(
            string applicationKey, Guid membershipId, CancellationToken cancellationToken)
        {
            FindCalls++;
            return Task.FromResult<CallingParticipantDescriptor?>(
                membershipId == callee
                    ? new CallingParticipantDescriptor(callee, applicationKey, "subject", "Callee", true)
                    : null);
        }
    }

    private static ClaimsPrincipal Principal(Guid membershipId, bool guest) => new(
        new ClaimsIdentity(
        [
            new Claim(ApplicationIdentityDefaults.ApplicationKeyClaim, BotGlobalApplications.Nqrb),
            new Claim(ApplicationIdentityDefaults.MembershipIdClaim, membershipId.ToString()),
            new Claim(ApplicationIdentityDefaults.GuestClaim, guest ? "true" : "false")
        ], ApplicationIdentityDefaults.Scheme));

    private sealed class TestHubCallerContext(ClaimsPrincipal user) : HubCallerContext
    {
        public override string ConnectionId => "nqrb-direct-call";
        public override string? UserIdentifier => null;
        public override ClaimsPrincipal User => user;
        public override IDictionary<object, object?> Items { get; } = new Dictionary<object, object?>();
        public override IFeatureCollection Features { get; } = new FeatureCollection();
        public override CancellationToken ConnectionAborted => CancellationToken.None;
        public override void Abort() { }
    }
}
