using System.Security.Claims;
using BotGlobal.Calling.Application;
using BotGlobal.Calling.Domain;
using BotGlobal.Calling.Infrastructure;
using BotGlobal.Calling.Realtime;
using BotGlobal.Contracts.Calling;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Notifications;
using Microsoft.Data.Sqlite;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Calling;

public sealed class CallingHubContactAndNotificationTests
{
    private static readonly Guid ApplicationId = Guid.NewGuid();

    [Fact]
    public async Task Batched_history_eligibility_translates_on_a_relational_database_and_honors_blocking()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        await using var db = new CallingDbContext(new DbContextOptionsBuilder<CallingDbContext>()
            .UseSqlite(connection).Options);
        await db.Database.EnsureCreatedAsync();
        var caller = Guid.NewGuid();
        var priorCaller = Guid.NewGuid();
        var saved = Guid.NewGuid();
        var blocked = Guid.NewGuid();
        await AddCompletedCallAsync(db, priorCaller, caller);
        await AddCompletedCallAsync(db, blocked, caller);
        db.NqrbContactEdges.Add(new NqrbContactEdge(BotGlobalApplications.Nqrb, caller, saved, TimeProvider.System.GetUtcNow()));
        db.NqrbBlockedAccounts.Add(new NqrbBlockedAccount(BotGlobalApplications.Nqrb, caller, blocked, TimeProvider.System.GetUtcNow()));
        await db.SaveChangesAsync();
        var eligibility = Eligibility(db, new AccountDirectory(caller, priorCaller, saved, blocked));

        var results = await eligibility.EvaluateManyAsync(caller, [priorCaller, saved, blocked], default);

        Assert.True(results[priorCaller].CanCall);
        Assert.True(results[priorCaller].CanAddContact);
        Assert.False(results[priorCaller].IsSavedContact);
        Assert.True(results[saved].CanCall);
        Assert.True(results[saved].IsSavedContact);
        Assert.False(results[saved].CanAddContact);
        Assert.False(results.ContainsKey(blocked));
    }

    [Fact]
    public async Task Nqrb_cannot_dial_an_unsaved_user_even_when_the_directory_knows_the_membership()
    {
        await using var db = CreateDb();
        var caller = Guid.NewGuid();
        var callee = Guid.NewGuid();
        var accounts = new AccountDirectory(caller, callee);
        var contacts = new NqrbContactBookService(db, accounts, TimeProvider.System);
        var eligibility = Eligibility(db, accounts);
        var directory = new ParticipantDirectory(callee);
        var registry = new CallSessionRegistry();
        registry.Connected("caller", Identity(caller));
        var notifications = new RecordingNotifications();
        var hub = CreateHub(registry, eligibility, directory, notifications, "caller", caller);

        var error = await Assert.ThrowsAsync<HubException>(() => hub.StartOutgoingCall(new(callee)));

        Assert.Equal("call_peer_unavailable", error.Message);
        Assert.Equal(0, directory.FindCalls);
        Assert.Empty(notifications.Items);

        await contacts.AddAsync(caller, callee, CancellationToken.None);
        var started = await hub.StartOutgoingCall(new(callee));

        Assert.NotEqual(Guid.Empty, started.CallId);
        Assert.Equal(1, directory.FindCalls);
        Assert.Equal(IncomingCallNotificationKind.Offered, Assert.Single(notifications.Items).Kind);
    }

    [Fact]
    public async Task Nqrb_can_dial_an_unsaved_prior_terminal_history_counterpart()
    {
        await using var db = CreateDb();
        var caller = Guid.NewGuid();
        var callee = Guid.NewGuid();
        var accounts = new AccountDirectory(caller, callee);
        await AddCompletedCallAsync(db, caller, callee);
        var directory = new ParticipantDirectory(callee);
        var registry = new CallSessionRegistry();
        registry.Connected("caller", Identity(caller));
        var notifications = new RecordingNotifications();
        var hub = CreateHub(registry, Eligibility(db, accounts), directory, notifications, "caller", caller);

        var started = await hub.StartOutgoingCall(new(callee));

        Assert.NotEqual(Guid.Empty, started.CallId);
        Assert.Equal(1, directory.FindCalls);
        Assert.Equal(IncomingCallNotificationKind.Offered, Assert.Single(notifications.Items).Kind);
    }

    [Fact]
    public async Task Nqrb_blocked_caller_cannot_ring_or_notify_the_recipient()
    {
        await using var db = CreateDb();
        var caller = Guid.NewGuid();
        var callee = Guid.NewGuid();
        var accounts = new AccountDirectory(caller, callee);
        var contacts = new NqrbContactBookService(db, accounts, TimeProvider.System);
        await contacts.AddAsync(caller, callee, CancellationToken.None);
        db.NqrbBlockedAccounts.Add(new NqrbBlockedAccount(BotGlobalApplications.Nqrb, callee, caller, TimeProvider.System.GetUtcNow()));
        await db.SaveChangesAsync();
        var registry = new CallSessionRegistry();
        registry.Connected("caller", Identity(caller));
        var directory = new ParticipantDirectory(callee);
        var notifications = new RecordingNotifications();
        var hub = CreateHub(registry, Eligibility(db, accounts), directory, notifications, "caller", caller);

        var error = await Assert.ThrowsAsync<HubException>(() => hub.StartOutgoingCall(new(callee)));

        Assert.Equal("call_peer_unavailable", error.Message);
        Assert.Equal(0, directory.FindCalls);
        Assert.Empty(notifications.Items);
    }

    [Theory]
    [InlineData(true, IncomingCallNotificationKind.AnsweredElsewhere)]
    [InlineData(false, IncomingCallNotificationKind.Cancelled)]
    public async Task Terminal_answer_or_rejection_clears_the_offer_on_other_devices(
        bool answer, IncomingCallNotificationKind expectedKind)
    {
        await using var db = CreateDb();
        var caller = Guid.NewGuid();
        var callee = Guid.NewGuid();
        var accounts = new AccountDirectory(caller, callee);
        var contacts = new NqrbContactBookService(db, accounts, TimeProvider.System);
        var eligibility = Eligibility(db, accounts);
        var directory = new ParticipantDirectory(callee);
        var registry = new CallSessionRegistry();
        registry.Connected("caller", Identity(caller));
        registry.Connected("callee-answering", Identity(callee));
        registry.Connected("callee-sibling", Identity(callee));
        var notifications = new RecordingNotifications();
        var clients = new RecordingClients();
        var callerHub = CreateHub(registry, eligibility, directory, notifications, "caller", caller, clients);
        await contacts.AddAsync(caller, callee, CancellationToken.None);
        var started = await callerHub.StartOutgoingCall(new(callee));
        var calleeHub = CreateHub(registry, eligibility, directory, notifications, "callee-answering", callee, clients);

        if (answer) await calleeHub.AnswerIncomingCall(new(started.CallId));
        else await calleeHub.RejectIncomingCall(new(started.CallId));

        Assert.Equal(expectedKind, notifications.Items.Last().Kind);
        Assert.Equal("callee-subject", notifications.Items.Last().RecipientSubjectId);
        Assert.Equal(BotGlobalApplications.Nqrb, notifications.Items.Last().ApplicationKey);
        Assert.Contains(clients.Sent, item => item.ConnectionId == "callee-sibling" && item.Method == "CallEnded");
        Assert.DoesNotContain(clients.Sent, item => item.ConnectionId == "callee-answering" && item.Method == "CallEnded");
    }

    [Fact]
    public async Task Browser_guest_leave_finishes_history_notifies_host_and_releases_invite()
    {
        await using var db = CreateDb();
        var registry = new CallSessionRegistry();
        var hostId = Guid.NewGuid();
        var host = Identity(hostId);
        registry.Connected("host", host);
        var invites = new NqrbGuestCallInviteService(
            registry, Options.Create(new NqrbGuestCallInviteOptions()), TimeProvider.System);
        var invite = invites.CreateHostInvite(host);
        var accepted = invites.Accept(invite.Capability, "Browser guest", true, "request-1").Value!;
        var guest = invites.AuthenticateGuestToken(accepted.GuestAccessToken)!;
        registry.Connected("guest", guest.Identity);
        var activity = new NoopActivity();
        var notifications = new RecordingNotifications();
        var clients = new RecordingClients();
        var hub = CreateHub(registry,
            Eligibility(db, new AccountDirectory(hostId, guest.Identity.MembershipId)),
            new ParticipantDirectory(hostId), notifications, "guest", guest.Identity.MembershipId,
            clients, invites, activity, GuestPrincipal(guest.Identity.MembershipId, invite.InviteId, accepted.CallId));

        await hub.EndCall(new EndCallRequest(accepted.CallId, "local"));

        Assert.Equal(1, activity.FinishCalls);
        Assert.False(registry.IsLiveCall(accepted.CallId));
        Assert.Contains(clients.Sent, item => item.ConnectionId == "host" && item.Method == "CallEnded");
        Assert.Equal(IncomingCallNotificationKind.Cancelled, Assert.Single(notifications.Items).Kind);
        Assert.False(invites.IsGuestAuthorized(invite.InviteId, guest.Identity.MembershipId, accepted.CallId));
    }

    private static CallingDbContext CreateDb() => new(
        new DbContextOptionsBuilder<CallingDbContext>()
            .UseInMemoryDatabase($"calling-hub-{Guid.NewGuid():N}").Options);

    private static NqrbCallEligibilityService Eligibility(
        CallingDbContext db,
        ICallingAccountDirectory accounts) =>
        new(db, new Applications(), accounts);

    private static async Task AddCompletedCallAsync(
        CallingDbContext db,
        Guid caller,
        Guid callee)
    {
        var call = new CallRecord(Guid.NewGuid(), ApplicationId, BotGlobalApplications.Nqrb,
            DateTimeOffset.Parse("2026-10-02T12:00:00Z"));
        call.Participants.Add(new CallParticipantRecord(call.Id, caller, CallParticipantRole.Initiator, "Caller"));
        call.Participants.Add(new CallParticipantRecord(call.Id, callee, CallParticipantRole.Recipient, "Callee"));
        call.Answer(DateTimeOffset.Parse("2026-10-02T12:00:05Z"));
        call.Finish(DurableCallOutcome.Completed, "local", DateTimeOffset.Parse("2026-10-02T12:01:00Z"));
        db.Calls.Add(call);
        await db.SaveChangesAsync();
    }

    private static CallingHub CreateHub(
        CallSessionRegistry registry,
        INqrbCallEligibilityService eligibility,
        ICallingParticipantDirectory directory,
        IIncomingCallNotificationDispatcher notifications,
        string connectionId,
        Guid membershipId,
        RecordingClients? clients = null,
        NqrbGuestCallInviteService? guestInvites = null,
        NoopActivity? activity = null,
        ClaimsPrincipal? principal = null) => new(
            registry, null!, guestInvites!, directory, eligibility, notifications,
            activity ?? new NoopActivity(), TimeProvider.System, NullLogger<CallingHub>.Instance)
        {
            Context = new TestContext(connectionId, principal ?? Principal(membershipId)),
            Clients = clients ?? new RecordingClients()
        };

    private sealed class Applications : IPlatformClientApplicationResolver
    {
        public Task<PlatformClientDescriptor?> FindByClientKeyAsync(string clientKey, CancellationToken cancellationToken) =>
            Task.FromResult<PlatformClientDescriptor?>(clientKey == BotGlobalApplications.Nqrb
                ? new(ApplicationId, BotGlobalApplications.Nqrb, "NQRB", true)
                : null);
    }

    private static ApplicationIdentityDescriptor Identity(Guid membershipId) => new(
        membershipId, null, membershipId == Guid.Empty ? "" : membershipId.ToString(),
        BotGlobalApplications.Nqrb, "Person", false);

    private static ClaimsPrincipal Principal(Guid membershipId) => new(new ClaimsIdentity(
    [
        new Claim(ApplicationIdentityDefaults.ApplicationKeyClaim, BotGlobalApplications.Nqrb),
        new Claim(ApplicationIdentityDefaults.MembershipIdClaim, membershipId.ToString()),
        new Claim(ApplicationIdentityDefaults.GuestClaim, "false")
    ], ApplicationIdentityDefaults.Scheme));

    private static ClaimsPrincipal GuestPrincipal(Guid membershipId, Guid inviteId, Guid callId) => new(new ClaimsIdentity(
    [
        new Claim(ApplicationIdentityDefaults.ApplicationKeyClaim, BotGlobalApplications.Nqrb),
        new Claim(ApplicationIdentityDefaults.MembershipIdClaim, membershipId.ToString()),
        new Claim(ApplicationIdentityDefaults.GuestClaim, "true"),
        new Claim(NqrbGuestCallAuthenticationDefaults.InviteIdClaim, inviteId.ToString()),
        new Claim(NqrbGuestCallAuthenticationDefaults.CallIdClaim, callId.ToString())
    ], NqrbGuestCallAuthenticationDefaults.Scheme));

    private sealed class TestContext(string connectionId, ClaimsPrincipal user) : HubCallerContext
    {
        public override string ConnectionId => connectionId;
        public override string? UserIdentifier => null;
        public override ClaimsPrincipal User => user;
        public override IDictionary<object, object?> Items { get; } = new Dictionary<object, object?>();
        public override IFeatureCollection Features { get; } = new FeatureCollection();
        public override CancellationToken ConnectionAborted => CancellationToken.None;
        public override void Abort() { }
    }

    private sealed class RecordingClients : IHubCallerClients
    {
        public List<(string ConnectionId, string Method)> Sent { get; } = [];
        public IClientProxy Caller => Client("caller");
        public IClientProxy Others => throw new NotSupportedException();
        public IClientProxy All => throw new NotSupportedException();
        public IClientProxy Client(string connectionId) => new RecordingClient(this, connectionId);
        public IClientProxy AllExcept(IReadOnlyList<string> excludedConnectionIds) => throw new NotSupportedException();
        public IClientProxy Clients(IReadOnlyList<string> connectionIds) => throw new NotSupportedException();
        public IClientProxy Group(string groupName) => throw new NotSupportedException();
        public IClientProxy GroupExcept(string groupName, IReadOnlyList<string> excludedConnectionIds) => throw new NotSupportedException();
        public IClientProxy Groups(IReadOnlyList<string> groupNames) => throw new NotSupportedException();
        public IClientProxy OthersInGroup(string groupName) => throw new NotSupportedException();
        public IClientProxy User(string userId) => throw new NotSupportedException();
        public IClientProxy Users(IReadOnlyList<string> userIds) => throw new NotSupportedException();

        private sealed class RecordingClient(RecordingClients owner, string connectionId) : IClientProxy
        {
            public Task SendCoreAsync(string method, object?[] args, CancellationToken cancellationToken = default)
            {
                owner.Sent.Add((connectionId, method));
                return Task.CompletedTask;
            }
        }
    }

    private sealed class RecordingNotifications : IIncomingCallNotificationDispatcher
    {
        public List<IncomingCallNotification> Items { get; } = [];
        public Task DispatchAsync(IncomingCallNotification notification, CancellationToken cancellationToken)
        {
            Items.Add(notification);
            return Task.CompletedTask;
        }
    }

    private sealed class ParticipantDirectory(Guid callee) : ICallingParticipantDirectory
    {
        public int FindCalls { get; private set; }
        public Task<IReadOnlyList<CallingParticipantDescriptor>> ListCallableAsync(
            string applicationKey, Guid currentMembershipId, CancellationToken cancellationToken) =>
            Task.FromResult<IReadOnlyList<CallingParticipantDescriptor>>([]);
        public Task<CallingParticipantDescriptor?> FindAsync(
            string applicationKey, Guid membershipId, CancellationToken cancellationToken)
        {
            FindCalls++;
            return Task.FromResult<CallingParticipantDescriptor?>(membershipId == callee
                ? new(callee, applicationKey, "callee-subject", "Callee", true)
                : null);
        }
    }

    private sealed class AccountDirectory(params Guid[] activeMemberships) : ICallingAccountDirectory
    {
        public Task<CallingAccountDescriptor?> FindActiveNonGuestAsync(
            string applicationKey, Guid membershipId, CancellationToken cancellationToken) =>
            Task.FromResult<CallingAccountDescriptor?>(IsActive(applicationKey, membershipId)
                ? new(membershipId, membershipId == activeMemberships.First() ? "Caller" : "Callee")
                : null);
        public Task<IReadOnlyList<CallingAccountDescriptor>> FindActiveNonGuestAsync(
            string applicationKey, IReadOnlyCollection<Guid> membershipIds, CancellationToken cancellationToken) =>
            Task.FromResult<IReadOnlyList<CallingAccountDescriptor>>(
                membershipIds
                    .Where(id => IsActive(applicationKey, id))
                    .Select(id => new CallingAccountDescriptor(id, id == activeMemberships.First() ? "Caller" : "Callee"))
                    .ToArray());
        public Task<CallingAccountSearchPage> SearchActiveNonGuestsAsync(
            string applicationKey, Guid currentMembershipId, string query, int page, int pageSize,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        private bool IsActive(string applicationKey, Guid membershipId) =>
            applicationKey == BotGlobalApplications.Nqrb && activeMemberships.Contains(membershipId);
    }

    private sealed class NoopActivity : ICallActivityService
    {
        public int FinishCalls { get; private set; }
        public Task StartAsync(CallSessionRegistry.Session session, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task AnswerAsync(CallSessionRegistry.Session session, DateTimeOffset at, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task FinishAsync(CallSessionRegistry.Session session, DateTimeOffset at, CancellationToken cancellationToken)
        {
            FinishCalls++;
            return Task.CompletedTask;
        }
        public Task JoinedAsync(CallSessionRegistry.Session session, Guid membershipId, DateTimeOffset at, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task<CallHistoryPage> ListAsync(string applicationKey, Guid membershipId, int page, int pageSize, CallHistoryFilter filter, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<CallHistoryDetail?> DetailAsync(string applicationKey, Guid membershipId, Guid callId, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<FinalizeUsageResult> FinalizeUsageAsync(string applicationKey, Guid membershipId, Guid callId, UsageSummary usage, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<UsagePeriodView> CurrentPeriodAsync(string applicationKey, Guid membershipId, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<UsagePeriodView> ResetAsync(string applicationKey, Guid membershipId, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<UsagePeriodView> ScheduleResetAsync(string applicationKey, Guid membershipId, DateTime localDateTime, string timeZoneId, CancellationToken cancellationToken) => throw new NotSupportedException();
    }
}
