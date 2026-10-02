using BotGlobal.Calling.Application;
using BotGlobal.Calling.Domain;
using BotGlobal.Calling.Infrastructure;
using BotGlobal.Calling.Realtime;
using BotGlobal.Communication.Application.MobileNotifications;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Calling;
using BotGlobal.Contracts.Notifications;
using BotGlobal.Notifications.Domain;
using BotGlobal.Notifications.Infrastructure.Persistence;
using BotGlobal.Pairing.Domain;
using BotGlobal.Pairing.Infrastructure.Persistence;
using BotGlobal.Pairing.Security;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Compliance;

public sealed class NqrbAccountDeletionHandlerTests
{
    [Fact]
    public async Task Pairing_deletion_removes_only_owned_device_data_and_rejects_old_credential()
    {
        await using var db = new PairingDbContext(new DbContextOptionsBuilder<PairingDbContext>()
            .UseInMemoryDatabase($"pairing-account-deletion-{Guid.NewGuid():N}").Options);
        var appId = Guid.NewGuid();
        var targetDeviceId = Guid.NewGuid();
        var otherDeviceId = Guid.NewGuid();
        var now = DateTimeOffset.UtcNow;
        var credentials = new MobileDeviceCredentialService();
        const string oldCredential = "test-device-credential";
        var target = new MobileDevice(targetDeviceId, appId, "user:target", "target-installation", "android",
            "Target phone", "1.0", credentials.Hash(oldCredential), now);
        var other = new MobileDevice(otherDeviceId, appId, "user:other", "other-installation", "android",
            "Other phone", "1.0", credentials.Hash("other-test-credential"), now);
        db.Devices.AddRange(target, other);
        db.PushRegistrations.AddRange(
            new MobilePushRegistration(targetDeviceId, "fcm", "target-fcm-test-value", now),
            new MobilePushRegistration(otherDeviceId, "fcm", "other-fcm-test-value", now));
        db.DeviceAuditEntries.Add(new MobileDeviceAuditEntry(
            Guid.NewGuid(), targetDeviceId, appId, MobileDeviceAuditKinds.EnrolledByApplicationIdentity,
            MobileDeviceAuditActorTypes.Device, "Target", null, now));
        db.ProfileSnapshots.AddRange(
            new MobileProfileSnapshot(Guid.NewGuid(), appId, "user:target", "Target", null, null, 1, now, now),
            new MobileProfileSnapshot(Guid.NewGuid(), appId, "user:other", "Other", null, null, 1, now, now));
        db.Challenges.Add(PairingChallenge.Create(appId, new byte[PairingChallenge.TokenHashBytes], null,
            "user:target", now, TimeSpan.FromMinutes(5)));
        await db.SaveChangesAsync();
        var resolver = new FixedApplicationResolver(appId);
        var revocation = new BotGlobal.Pairing.Application.PairingAccountAccessRevocationHandler(
            db, resolver, TimeProvider.System);
        var handler = new BotGlobal.Pairing.Application.PairingAccountDeletionHandler(db, resolver);
        var scope = Scope(targetDeviceId);

        await revocation.DeleteAsync(scope, CancellationToken.None);
        Assert.NotNull(await db.Devices.FindAsync(targetDeviceId));
        Assert.NotNull((await db.Devices.FindAsync(targetDeviceId))!.RevokedAtUtc);
        Assert.NotNull((await db.PushRegistrations.SingleAsync(
            item => item.MobileDeviceId == targetDeviceId)).InvalidatedAtUtc);
        Assert.Null(await new MobileDeviceAuthenticator(db, credentials)
            .AuthenticateAsync(oldCredential, CancellationToken.None));
        await handler.DeleteAsync(scope, CancellationToken.None);
        await handler.DeleteAsync(scope, CancellationToken.None);

        Assert.Null(await db.Devices.FindAsync(targetDeviceId));
        Assert.Empty(await db.PushRegistrations.Where(item => item.MobileDeviceId == targetDeviceId).ToListAsync());
        Assert.Empty(await db.DeviceAuditEntries.Where(item => item.MobileDeviceId == targetDeviceId).ToListAsync());
        Assert.Empty(await db.ProfileSnapshots.Where(item => item.ExternalSubjectId == "user:target").ToListAsync());
        Assert.Empty(await db.Challenges.Where(item => item.ExternalSubjectId == "user:target").ToListAsync());
        Assert.NotNull(await db.Devices.FindAsync(otherDeviceId));
        Assert.Single(await db.PushRegistrations.Where(item => item.MobileDeviceId == otherDeviceId).ToListAsync());
        Assert.Single(await db.ProfileSnapshots.Where(item => item.ExternalSubjectId == "user:other").ToListAsync());
    }

    [Fact]
    public async Task Notification_deletion_removes_target_delivery_rows_and_preserves_other_device()
    {
        await using var db = new NotificationsDbContext(new DbContextOptionsBuilder<NotificationsDbContext>()
            .UseInMemoryDatabase($"notification-account-deletion-{Guid.NewGuid():N}").Options);
        var appId = Guid.NewGuid();
        var campaignId = Guid.NewGuid();
        var targetDeviceId = Guid.NewGuid();
        var otherDeviceId = Guid.NewGuid();
        var now = DateTimeOffset.UtcNow;
        var target = NotificationRecipient.Create(appId, campaignId, targetDeviceId, "target-installation",
            "android", "Target phone", now, now.AddHours(1));
        var other = NotificationRecipient.Create(appId, campaignId, otherDeviceId, "other-installation",
            "android", "Other phone", now, now.AddHours(1));
        target.DeliveryAttempts.Add(NotificationDeliveryAttempt.Create(Guid.NewGuid(), target.Id, appId,
            campaignId, targetDeviceId, target.DeliveryKey, 1, Guid.NewGuid(), now));
        db.Recipients.AddRange(target, other);
        await db.SaveChangesAsync();
        var handler = new BotGlobal.Notifications.Application.NotificationAccountDeletionHandler(db);

        await handler.DeleteAsync(Scope(targetDeviceId), CancellationToken.None);
        await handler.DeleteAsync(Scope(targetDeviceId), CancellationToken.None);

        Assert.Null(await db.Recipients.FindAsync(target.Id));
        Assert.Empty(await db.DeliveryAttempts.Where(item => item.MobileDeviceId == targetDeviceId).ToListAsync());
        Assert.NotNull(await db.Recipients.FindAsync(other.Id));
    }

    [Fact]
    public async Task Calling_deletion_disconnects_target_and_anonymizes_only_shared_history()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        await using var db = new CallingDbContext(new DbContextOptionsBuilder<CallingDbContext>()
            .UseSqlite(connection).Options);
        await db.Database.EnsureCreatedAsync();
        var appId = Guid.NewGuid();
        var targetId = TargetMembershipId;
        var otherId = Guid.NewGuid();
        var unrelatedId = Guid.NewGuid();
        var now = DateTimeOffset.UtcNow;
        var shared = NewCall(appId, now, (targetId, "Target"), (otherId, "Other"));
        shared.UsageReports.Add(new CallUsageReport(shared.Id, targetId, 10, 20, 30, now));
        shared.UsageReports.Add(new CallUsageReport(shared.Id, otherId, 40, 50, 60, now));
        var solo = NewCall(appId, now, (targetId, "Target"));
        var unrelated = NewCall(appId, now, (unrelatedId, "Unrelated"));
        db.Calls.AddRange(shared, solo, unrelated);
        db.UsagePeriods.AddRange(
            new UsageCounterPeriod(appId, targetId, now, UsagePeriodResetReason.Initial),
            new UsageCounterPeriod(appId, unrelatedId, now, UsagePeriodResetReason.Initial));
        db.NqrbBlockedAccounts.AddRange(
            new NqrbBlockedAccount("nqrb", targetId, otherId, now),
            new NqrbBlockedAccount("nqrb", otherId, targetId, now),
            new NqrbBlockedAccount("nqrb", unrelatedId, otherId, now));
        await db.SaveChangesAsync();

        var sessions = new CallSessionRegistry();
        sessions.Connected("target-connection", Identity(targetId, "Target"));
        sessions.Connected("other-connection", Identity(otherId, "Other"));
        sessions.Start("target-connection", new CallingParticipantDescriptor(
            otherId, "nqrb", $"user:{otherId:N}", "Other", true), now, TimeSpan.FromMinutes(1));
        var activity = new RecordingCallActivity();
        var guestInvites = new NqrbGuestCallInviteService(
            sessions,
            Options.Create(new NqrbGuestCallInviteOptions()),
            TimeProvider.System);
        var handler = new BotGlobal.Calling.Application.CallingAccountDeletionHandler(
            sessions,
            guestInvites,
            activity,
            new RecordingHubContext(),
            new CallingAccountDataEraser(db),
            TimeProvider.System);

        await handler.DeleteAsync(Scope(), CancellationToken.None);
        await handler.DeleteAsync(Scope(), CancellationToken.None);

        Assert.False(sessions.IsOnline(targetId, "nqrb"));
        Assert.Equal("account_deleted", Assert.Throws<InvalidOperationException>(
            () => sessions.Connected("new-target", Identity(targetId, "Target"))).Message);
        Assert.True(sessions.IsOnline(otherId, "nqrb"));
        Assert.Equal(1, activity.Finished);
        Assert.Null(await db.Calls.FindAsync(solo.Id));
        Assert.NotNull(await db.Calls.FindAsync(unrelated.Id));
        var retained = await db.Calls.Include(item => item.Participants).Include(item => item.UsageReports)
            .SingleAsync(item => item.Id == shared.Id);
        Assert.DoesNotContain(retained.Participants, item => item.MembershipId == targetId);
        Assert.Contains(retained.Participants, item => item.DisplayNameSnapshot == "Deleted NQRB account");
        Assert.Contains(retained.Participants, item => item.MembershipId == otherId && item.DisplayNameSnapshot == "Other");
        Assert.DoesNotContain(retained.UsageReports, item => item.MembershipId == targetId);
        Assert.Contains(retained.UsageReports, item => item.MembershipId == otherId);
        Assert.Empty(await db.UsagePeriods.Where(item => item.MembershipId == targetId).ToListAsync());
        Assert.Single(await db.UsagePeriods.Where(item => item.MembershipId == unrelatedId).ToListAsync());
        Assert.Single(await db.NqrbBlockedAccounts.ToListAsync());
    }

    [Fact]
    public async Task Calling_deletion_completes_active_guest_call_without_durable_finish()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        await using var db = new CallingDbContext(new DbContextOptionsBuilder<CallingDbContext>()
            .UseSqlite(connection).Options);
        await db.Database.EnsureCreatedAsync();
        var host = Identity(TargetMembershipId, "Host");
        var now = DateTimeOffset.UtcNow;
        var sessions = new CallSessionRegistry();
        sessions.Connected("host-connection", host);
        var guestInvites = new NqrbGuestCallInviteService(
            sessions,
            Options.Create(new NqrbGuestCallInviteOptions()),
            TimeProvider.System);
        var invite = guestInvites.CreateHostInvite(host);
        var accepted = guestInvites.Accept(invite.Capability, "Browser guest", true, "request-1").Value!;
        var authenticatedGuest = guestInvites.AuthenticateGuestToken(accepted.GuestAccessToken)!;
        sessions.Connected("guest-connection", authenticatedGuest.Identity);
        sessions.Answer("host-connection", accepted.CallId, now);
        var activity = new RecordingCallActivity();
        var hub = new RecordingHubContext();
        var handler = new BotGlobal.Calling.Application.CallingAccountDeletionHandler(
            sessions,
            guestInvites,
            activity,
            hub,
            new CallingAccountDataEraser(db),
            TimeProvider.System);

        await handler.DeleteAsync(Scope(), CancellationToken.None);
        await handler.DeleteAsync(Scope(), CancellationToken.None);

        Assert.False(guestInvites.IsGuestAuthorized(
            invite.InviteId,
            authenticatedGuest.Identity.MembershipId,
            accepted.CallId));
        Assert.False(sessions.IsLiveCall(accepted.CallId));
        Assert.Equal(0, activity.Finished);
        var sent = Assert.Single(hub.Clients.Sent);
        Assert.Equal("guest-connection", sent.ConnectionId);
        Assert.Equal("CallEnded", sent.Method);
        var ended = Assert.IsType<CallEndedEvent>(Assert.Single(sent.Args));
        Assert.Equal(accepted.CallId, ended.CallId);
        Assert.Equal("account_deleted", ended.Reason);
    }

    [Fact]
    public async Task Communication_deletion_blocks_reconnect_for_target_device_only()
    {
        var target = Guid.NewGuid();
        var other = Guid.NewGuid();
        var registry = new MobileNotificationConnectionRegistry();
        registry.Connected(target);
        registry.Connected(other);
        var handler = new CommunicationAccountDeletionHandler(registry);

        await handler.DeleteAsync(Scope(target), CancellationToken.None);
        registry.Connected(target);

        Assert.False(registry.IsConnected(target));
        Assert.True(registry.IsConnected(other));
    }

    private static readonly Guid TargetMembershipId = Guid.NewGuid();

    private static ApplicationAccountDeletionScope Scope(params Guid[] deviceIds) => new(
        new ApplicationAccountDeletionIdentity(
            TargetMembershipId, Guid.NewGuid(), "user:target", "nqrb"),
        deviceIds);

    private static ApplicationIdentityDescriptor Identity(Guid membershipId, string name) =>
        new(membershipId, Guid.NewGuid(), $"user:{membershipId:N}", "nqrb", name, false);

    private static CallRecord NewCall(
        Guid applicationId,
        DateTimeOffset now,
        params (Guid MembershipId, string Name)[] participants)
    {
        var call = new CallRecord(Guid.NewGuid(), applicationId, "nqrb", now);
        foreach (var (membershipId, name) in participants)
            call.Participants.Add(new CallParticipantRecord(
                call.Id, membershipId, CallParticipantRole.Initiator, name));
        return call;
    }

    private sealed class FixedApplicationResolver(Guid applicationId) : IPlatformClientApplicationResolver
    {
        public Task<PlatformClientDescriptor?> FindByClientKeyAsync(
            string clientKey,
            CancellationToken cancellationToken) => Task.FromResult<PlatformClientDescriptor?>(
            new PlatformClientDescriptor(applicationId, clientKey, "NQRB", true));
    }

    private sealed class RecordingCallActivity : ICallActivityService
    {
        public int Finished { get; private set; }
        public Task StartAsync(CallSessionRegistry.Session session, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task AnswerAsync(CallSessionRegistry.Session session, DateTimeOffset at, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task JoinedAsync(CallSessionRegistry.Session session, Guid membershipId, DateTimeOffset at, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task FinishAsync(CallSessionRegistry.Session session, DateTimeOffset at, CancellationToken cancellationToken) { Finished++; return Task.CompletedTask; }
        public Task<CallHistoryPage> ListAsync(string applicationKey, Guid membershipId, int page, int pageSize, CallHistoryFilter filter, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<CallHistoryDetail?> DetailAsync(string applicationKey, Guid membershipId, Guid callId, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<FinalizeUsageResult> FinalizeUsageAsync(string applicationKey, Guid membershipId, Guid callId, UsageSummary usage, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<UsagePeriodView> CurrentPeriodAsync(string applicationKey, Guid membershipId, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<UsagePeriodView> ResetAsync(string applicationKey, Guid membershipId, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<UsagePeriodView> ScheduleResetAsync(string applicationKey, Guid membershipId, DateTime localDateTime, string timeZoneId, CancellationToken cancellationToken) => throw new NotSupportedException();
    }

    private sealed class RecordingHubContext : IHubContext<CallingHub>
    {
        public RecordingHubClients Clients { get; } = new();
        IHubClients IHubContext<CallingHub>.Clients => Clients;
        public IGroupManager Groups { get; } = new RecordingGroups();
    }

    private sealed class RecordingHubClients : IHubClients
    {
        private readonly RecordingClientProxy proxy;
        public RecordingHubClients() => proxy = new RecordingClientProxy(Sent);
        public List<SentHubMessage> Sent { get; } = [];
        public IClientProxy All => Proxy;
        public IClientProxy AllExcept(IReadOnlyList<string> excludedConnectionIds) => Proxy;
        public IClientProxy Client(string connectionId) => new RecordingClientProxy(Sent, connectionId);
        public IClientProxy Clients(IReadOnlyList<string> connectionIds) => Proxy;
        public IClientProxy Group(string groupName) => Proxy;
        public IClientProxy GroupExcept(string groupName, IReadOnlyList<string> excludedConnectionIds) => Proxy;
        public IClientProxy Groups(IReadOnlyList<string> groupNames) => Proxy;
        public IClientProxy User(string userId) => Proxy;
        public IClientProxy Users(IReadOnlyList<string> userIds) => Proxy;
        private IClientProxy Proxy => proxy;
    }

    private sealed record SentHubMessage(string? ConnectionId, string Method, object?[] Args);

    private sealed class RecordingClientProxy(List<SentHubMessage> sent, string? connectionId = null) : IClientProxy
    {
        public Task SendCoreAsync(string method, object?[] args, CancellationToken cancellationToken = default)
        {
            sent.Add(new SentHubMessage(connectionId, method, args));
            return Task.CompletedTask;
        }
    }

    private sealed class RecordingGroups : IGroupManager
    {
        public Task AddToGroupAsync(string connectionId, string groupName, CancellationToken cancellationToken = default) => Task.CompletedTask;
        public Task RemoveFromGroupAsync(string connectionId, string groupName, CancellationToken cancellationToken = default) => Task.CompletedTask;
    }
}
