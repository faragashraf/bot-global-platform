using BotGlobal.Communication.Application.MobileNotifications;
using BotGlobal.Communication.Application.MobileNotifications.Push;
using BotGlobal.Communication.Contracts.MobileNotifications;
using BotGlobal.Communication.Hubs;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Notifications;
using BotGlobal.Notifications.Application;
using BotGlobal.Notifications.Application.Processing;
using BotGlobal.Notifications.Domain;
using BotGlobal.Notifications.Infrastructure.Persistence;
using BotGlobal.Pairing.Application;
using BotGlobal.Pairing.Application.PushRegistrations;
using BotGlobal.Pairing.Domain;
using BotGlobal.Pairing.Infrastructure.Persistence;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Communication;

public sealed class CampaignMobileNotificationTransportTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Permanent_destination_rejection_then_another_campaign_is_bounded_or_uses_replacement(bool replaceDuringRetry)
    {
        var time = new MutableTimeProvider(DateTimeOffset.UtcNow);
        var applicationId = Guid.NewGuid();
        await using var pairing = new PairingDbContext(new DbContextOptionsBuilder<PairingDbContext>()
            .UseInMemoryDatabase($"destination-lifecycle-{Guid.NewGuid():N}").Options);
        var device = new MobileDevice(Guid.NewGuid(), applicationId, "synthetic-subject", "synthetic-installation",
            "android", "Synthetic device", "1.0", new byte[32], time.GetUtcNow());
        pairing.Devices.Add(device);
        pairing.PushRegistrations.Add(new MobilePushRegistration(device.Id, "fcm", "old-synthetic-route", time.GetUtcNow()));
        await pairing.SaveChangesAsync();
        var registrations = new MobilePushRegistrationService(pairing, new MobileDeviceAuditRecorder(pairing), time);
        var push = new RecordingPushDispatcher(new ApplicationPushDispatchResult(
            ApplicationPushDispatchKind.PermanentFailure, "fcm-unregistered", InvalidatesDestination: true));
        var transport = CreateTransport(new MobileNotificationConnectionRegistry(), new RecordingClientProxy(),
            new MobilePushDestinationResolver(pairing), new DeviceStateReader(true, false), push, registrations);
        await using var notifications = new NotificationsDbContext(new DbContextOptionsBuilder<NotificationsDbContext>()
            .UseInMemoryDatabase($"campaign-lifecycle-{Guid.NewGuid():N}").Options);
        var options = Options.Create(new NotificationCampaignOptions
        {
            Retry = new NotificationRetryOptions { MaximumAttempts = 3, InitialDelaySeconds = 1 }
        });
        var first = Campaign(applicationId, time.GetUtcNow());
        var firstRecipient = NotificationRecipient.Create(applicationId, first.Id, device.Id,
            "synthetic-installation", "android", null, time.GetUtcNow(), first.ExpiresAtUtc);
        notifications.AddRange(first, firstRecipient);
        await notifications.SaveChangesAsync();

        await ProcessNext();
        Assert.Equal(NotificationRecipientStatus.FailedPermanent, firstRecipient.Status);
        Assert.NotNull((await pairing.PushRegistrations.SingleAsync()).InvalidatedAtUtc);
        Assert.Equal(1, push.Calls);

        var second = Campaign(applicationId, time.GetUtcNow());
        var secondRecipient = NotificationRecipient.Create(applicationId, second.Id, device.Id,
            "synthetic-installation", "android", null, time.GetUtcNow(), second.ExpiresAtUtc);
        notifications.AddRange(second, secondRecipient);
        await notifications.SaveChangesAsync();
        await ProcessNext();
        Assert.Equal(NotificationRecipientStatus.RetryScheduled, secondRecipient.Status);
        Assert.Equal("no-active-route", secondRecipient.LastSafeErrorCode);
        Assert.Equal(1, push.Calls); // the invalid destination was not sent again

        if (replaceDuringRetry)
        {
            await registrations.RegisterAsync(new NotificationApplicationContext(applicationId), device.Id,
                new RegisterMobilePushRequest("fcm", "replacement-synthetic-route"), CancellationToken.None);
            push.Result = new ApplicationPushDispatchResult(ApplicationPushDispatchKind.Accepted);
        }

        while (secondRecipient.NextAttemptAtUtc is DateTimeOffset due)
        {
            time.Set(due);
            await ProcessNext();
        }

        Assert.Equal(replaceDuringRetry ? NotificationRecipientStatus.FcmAccepted : NotificationRecipientStatus.FailedPermanent,
            secondRecipient.Status);
        Assert.Equal(replaceDuringRetry ? 2 : 3, secondRecipient.AttemptCount);
        Assert.Equal(replaceDuringRetry ? 2 : 1, push.Calls);
        Assert.Equal(applicationId, push.LastMessage!.Application.ApplicationId);
        if (replaceDuringRetry) Assert.Equal("replacement-synthetic-route", push.LastMessage.RegistrationToken);
        else Assert.Equal("retry-budget-exhausted", secondRecipient.LastSafeErrorCode);

        var summary = new NotificationCampaignSummaryService(notifications);
        await summary.RefreshAsync(first.Id, time.GetUtcNow(), CancellationToken.None);
        await summary.RefreshAsync(second.Id, time.GetUtcNow(), CancellationToken.None);
        Assert.Equal(NotificationCampaignStatus.CompletedWithFailures, first.Status);
        Assert.Equal(replaceDuringRetry ? NotificationCampaignStatus.Completed : NotificationCampaignStatus.CompletedWithFailures,
            second.Status);
        notifications.ChangeTracker.Clear();
        Assert.Empty(await new NotificationWorkClaimer(notifications).ClaimRecipientsAsync(
            time.GetUtcNow().AddMinutes(10), time.GetUtcNow().AddMinutes(12), 10, CancellationToken.None));
        Assert.Equal(replaceDuringRetry ? 3 : 4, await notifications.DeliveryAttempts.CountAsync());

        async Task ProcessNext()
        {
            var claim = Assert.Single(await new NotificationWorkClaimer(notifications).ClaimRecipientsAsync(
                time.GetUtcNow(), time.GetUtcNow().AddMinutes(2), 10, CancellationToken.None));
            var processor = new NotificationDeliveryAttemptProcessor(notifications, transport, options, time,
                NullLogger<NotificationDeliveryAttemptProcessor>.Instance);
            Assert.True((await processor.ProcessAsync(claim, CancellationToken.None)).Processed);
            Assert.False((await processor.ProcessAsync(claim, CancellationToken.None)).Processed);
        }
    }

    [Fact]
    public async Task Replacement_registered_during_rejected_send_is_preserved_and_delivery_can_retry()
    {
        var now = DateTimeOffset.UtcNow;
        var application = new NotificationApplicationContext(Guid.NewGuid());
        await using var db = new PairingDbContext(new DbContextOptionsBuilder<PairingDbContext>()
            .UseInMemoryDatabase($"replacement-in-flight-{Guid.NewGuid():N}").Options);
        var device = new MobileDevice(Guid.NewGuid(), application.ApplicationId, "synthetic-subject",
            "synthetic-installation", "android", "Synthetic device", "1.0", new byte[32], now);
        db.Devices.Add(device);
        db.PushRegistrations.Add(new MobilePushRegistration(device.Id, "fcm", "old-synthetic-route", now));
        await db.SaveChangesAsync();
        var registrations = new MobilePushRegistrationService(db, new MobileDeviceAuditRecorder(db), TimeProvider.System);
        var push = new RecordingPushDispatcher(new ApplicationPushDispatchResult(
            ApplicationPushDispatchKind.PermanentFailure, "fcm-unregistered", InvalidatesDestination: true))
        {
            BeforeResult = () => registrations.RegisterAsync(application, device.Id,
                new RegisterMobilePushRequest("fcm", "replacement-synthetic-route"), CancellationToken.None)
        };
        var transport = CreateTransport(new MobileNotificationConnectionRegistry(), new RecordingClientProxy(),
            new MobilePushDestinationResolver(db), new DeviceStateReader(true, false), push, registrations);
        var request = Request(device.Id) with { Application = application };

        var rejected = await transport.DispatchAsync(request, CancellationToken.None);
        Assert.Equal(MobileNotificationTransportOutcomeKind.TransientFailure, rejected.Kind);
        Assert.Equal("destination-replaced", rejected.SafeErrorCode);
        Assert.Null((await db.PushRegistrations.SingleAsync()).InvalidatedAtUtc);

        push.BeforeResult = null;
        push.Result = new ApplicationPushDispatchResult(ApplicationPushDispatchKind.Accepted);
        Assert.Equal(MobileNotificationTransportOutcomeKind.FcmAccepted,
            (await transport.DispatchAsync(request, CancellationToken.None)).Kind);
        Assert.Equal("replacement-synthetic-route", push.LastMessage!.RegistrationToken);
    }

    [Fact]
    public async Task Connected_device_is_signalr_dispatched_without_fcm()
    {
        var deviceId = Guid.NewGuid();
        var connections = new MobileNotificationConnectionRegistry();
        connections.Connected(deviceId);
        var proxy = new RecordingClientProxy();
        var push = new RecordingPushDispatcher();
        var transport = CreateTransport(
            connections,
            proxy,
            new PushResolver(null),
            new DeviceStateReader(true, false),
            push);

        var outcome = await transport.DispatchAsync(
            Request(deviceId),
            CancellationToken.None);

        Assert.Equal(MobileNotificationTransportOutcomeKind.SignalRDispatched, outcome.Kind);
        Assert.Equal(1, proxy.SendCalls);
        Assert.Equal(0, push.Calls);
    }

    [Fact]
    public async Task Offline_push_capable_device_is_fcm_accepted_with_caller_ttl()
    {
        var deviceId = Guid.NewGuid();
        var push = new RecordingPushDispatcher();
        var transport = CreateTransport(
            new MobileNotificationConnectionRegistry(),
            new RecordingClientProxy(),
            new PushResolver(new MobilePushDestination(deviceId, "fcm", "sensitive-test-token")),
            new DeviceStateReader(true, false),
            push);

        var request = Request(deviceId) with { TimeToLive = TimeSpan.FromDays(9) };
        var outcome = await transport.DispatchAsync(request, CancellationToken.None);

        Assert.Equal(MobileNotificationTransportOutcomeKind.FcmAccepted, outcome.Kind);
        Assert.Equal(TimeSpan.FromDays(9), push.LastMessage!.TimeToLive);
        Assert.Equal("provider-message-id", outcome.ProviderMessageId);
        Assert.Equal("Fcm", outcome.Transport);
        Assert.Equal(
            request.NotificationId,
            push.LastMessage.Data["notificationId"]);
        Assert.Equal(
            request.DeliveryAttemptId.ToString("N"),
            push.LastMessage.Data["deliveryAttemptId"]);
        Assert.Equal(MobileNotificationPriority.Normal, push.LastMessage.Priority);
    }

    [Fact]
    public async Task High_priority_campaign_preserves_semantic_priority_for_provider_dispatch()
    {
        var deviceId = Guid.NewGuid();
        var push = new RecordingPushDispatcher();
        var transport = CreateTransport(
            new MobileNotificationConnectionRegistry(),
            new RecordingClientProxy(),
            new PushResolver(new MobilePushDestination(deviceId, "fcm", "test-token")),
            new DeviceStateReader(true, false),
            push);

        var request = Request(deviceId) with
        {
            Priority = (int)MobileNotificationPriority.High
        };

        var outcome = await transport.DispatchAsync(request, CancellationToken.None);

        Assert.Equal(MobileNotificationTransportOutcomeKind.FcmAccepted, outcome.Kind);
        Assert.Equal(MobileNotificationPriority.High, push.LastMessage!.Priority);
        Assert.Equal("High", push.LastMessage.Data["priority"]);
    }

    [Fact]
    public async Task Offline_device_without_push_route_remains_retryable()
    {
        var transport = CreateTransport(
            new MobileNotificationConnectionRegistry(),
            new RecordingClientProxy(),
            new PushResolver(null),
            new DeviceStateReader(true, false),
            new RecordingPushDispatcher());

        var outcome = await transport.DispatchAsync(
            Request(Guid.NewGuid()),
            CancellationToken.None);

        Assert.Equal(MobileNotificationTransportOutcomeKind.NoAvailableRoute, outcome.Kind);
    }

    [Fact]
    public async Task Unregistered_fid_is_invalidated_with_application_scope()
    {
        var deviceId = Guid.NewGuid();
        var invalidator = new RecordingPushInvalidator();
        var transport = CreateTransport(
            new MobileNotificationConnectionRegistry(),
            new RecordingClientProxy(),
            new PushResolver(new MobilePushDestination(deviceId, "fcm", "expired-fid")),
            new DeviceStateReader(true, false),
            new RecordingPushDispatcher(
                new ApplicationPushDispatchResult(
                    ApplicationPushDispatchKind.PermanentFailure,
                    "fcm-unregistered",
                    InvalidatesDestination: true)),
            invalidator);
        var request = Request(deviceId);

        var outcome = await transport.DispatchAsync(request, CancellationToken.None);

        Assert.Equal(MobileNotificationTransportOutcomeKind.PermanentFailure, outcome.Kind);
        Assert.Equal(request.Application.ApplicationId, invalidator.ApplicationId);
        Assert.Equal(deviceId, invalidator.DeviceId);
        Assert.Equal("fcm", invalidator.Provider);
        Assert.Equal("expired-fid", invalidator.RejectedRegistrationToken);
    }

    [Fact]
    public async Task Connected_delivery_exception_is_ambiguous_after_side_effect_starts()
    {
        var deviceId = Guid.NewGuid();
        var connections = new MobileNotificationConnectionRegistry();
        connections.Connected(deviceId);
        var proxy = new RecordingClientProxy { ThrowOnSend = true };
        var transport = CreateTransport(
            connections,
            proxy,
            new PushResolver(null),
            new DeviceStateReader(true, false),
            new RecordingPushDispatcher());

        var outcome = await transport.DispatchAsync(
            Request(deviceId),
            CancellationToken.None);

        Assert.Equal(MobileNotificationTransportOutcomeKind.Ambiguous, outcome.Kind);
        Assert.Equal("transport-outcome-unknown", outcome.SafeErrorCode);
    }

    [Theory]
    [InlineData(false, true)]
    [InlineData(true, true)]
    public async Task Missing_cross_client_or_revoked_device_is_never_dispatched(
        bool existsForPlatform,
        bool revoked)
    {
        var proxy = new RecordingClientProxy();
        var push = new RecordingPushDispatcher();
        var transport = CreateTransport(
            new MobileNotificationConnectionRegistry(),
            proxy,
            new PushResolver(new MobilePushDestination(Guid.NewGuid(), "fcm", "token")),
            new DeviceStateReader(existsForPlatform, revoked),
            push);

        var outcome = await transport.DispatchAsync(
            Request(Guid.NewGuid()),
            CancellationToken.None);

        Assert.Equal(MobileNotificationTransportOutcomeKind.DeviceRevoked, outcome.Kind);
        Assert.Equal(0, proxy.SendCalls);
        Assert.Equal(0, push.Calls);
    }

    private static NotificationCampaign Campaign(Guid applicationId, DateTimeOffset now)
    {
        var campaign = NotificationCampaign.Create(applicationId, "synthetic-app", "Synthetic application",
            NotificationAudienceKind.AllCurrentActiveDevices, now, "عنوان", "Title", "نص", "Body", "general",
            NotificationPriority.Normal, Guid.NewGuid().ToString("N"), new string('A', 64), Guid.NewGuid(),
            "Synthetic administrator", now, now.AddDays(1), 1, 1, 1);
        campaign.ClaimAudience(Guid.NewGuid(), now.AddMinutes(2), now);
        campaign.AdvanceAudience(null, 0, true);
        return campaign;
    }

    private sealed class MutableTimeProvider(DateTimeOffset now) : TimeProvider
    {
        private DateTimeOffset current = now;
        public override DateTimeOffset GetUtcNow() => current;
        public void Set(DateTimeOffset value) => current = value;
    }

    private static CampaignMobileNotificationTransport CreateTransport(
        IMobileNotificationConnectionRegistry connections,
        RecordingClientProxy proxy,
        IMobilePushDestinationResolver resolver,
        IMobileBroadcastAudienceReader audience,
        IApplicationPushNotificationDispatcher push,
        IMobilePushDestinationInvalidator? invalidator = null)
    {
        var hubContext = new TestHubContext(proxy);
        return new CampaignMobileNotificationTransport(
            new SignalRMobileNotificationDelivery(hubContext, connections),
            connections,
            resolver,
            invalidator ?? new RecordingPushInvalidator(),
            audience,
            push);
    }

    private static MobileNotificationTransportRequest Request(Guid deviceId) => new(
        Guid.NewGuid(),
        new NotificationApplicationContext(Guid.NewGuid()),
        deviceId,
        "installation",
        "android",
        "Device",
        Guid.NewGuid().ToString("N"),
        "عنوان",
        "Title",
        "نص",
        "Body",
        "general",
        1,
        TimeSpan.FromDays(7));

    private sealed class DeviceStateReader(bool exists, bool revoked)
        : IMobileBroadcastAudienceReader
    {
        public Task<MobileBroadcastAudiencePreview> PreviewAsync(NotificationApplicationContext application, DateTimeOffset audienceAsOfUtc, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<MobileBroadcastAudiencePage> ReadPageAsync(NotificationApplicationContext application, DateTimeOffset audienceAsOfUtc, Guid? afterDeviceId, int pageSize, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<MobileBroadcastDeviceState> GetCurrentDeviceStateAsync(NotificationApplicationContext application, Guid deviceId, CancellationToken cancellationToken) => Task.FromResult(new MobileBroadcastDeviceState(exists, revoked));
    }

    private sealed class PushResolver(MobilePushDestination? destination)
        : IMobilePushDestinationResolver
    {
        public Task<MobilePushDestination?> ResolveActiveAsync(NotificationApplicationContext application, Guid deviceId, string provider, CancellationToken cancellationToken) => Task.FromResult(destination);
    }

    private sealed class RecordingPushDispatcher(
        ApplicationPushDispatchResult? result = null)
        : IApplicationPushNotificationDispatcher
    {
        public int Calls { get; private set; }
        public ApplicationPushMessage? LastMessage { get; private set; }
        public ApplicationPushDispatchResult Result { get; set; } = result ?? new ApplicationPushDispatchResult(
            ApplicationPushDispatchKind.Accepted, ProviderMessageId: "provider-message-id");
        public Func<Task>? BeforeResult { get; set; }
        public async Task<ApplicationPushDispatchResult> DispatchAsync(
            ApplicationPushMessage message,
            CancellationToken cancellationToken)
        {
            Calls++;
            LastMessage = message;
            if (BeforeResult is not null) await BeforeResult();
            return Result;
        }
    }

    private sealed class RecordingPushInvalidator
        : IMobilePushDestinationInvalidator
    {
        public Guid? ApplicationId { get; private set; }
        public Guid? DeviceId { get; private set; }
        public string? Provider { get; private set; }
        public string? RejectedRegistrationToken { get; private set; }

        public Task InvalidateAsync(
            NotificationApplicationContext application,
            Guid deviceId,
            string provider,
            string rejectedRegistrationToken,
            string safeReason,
            CancellationToken cancellationToken)
        {
            ApplicationId = application.ApplicationId;
            DeviceId = deviceId;
            Provider = provider;
            RejectedRegistrationToken = rejectedRegistrationToken;
            return Task.CompletedTask;
        }
    }

    private sealed class RecordingClientProxy : IClientProxy
    {
        public int SendCalls { get; private set; }
        public bool ThrowOnSend { get; init; }
        public Task SendCoreAsync(string method, object?[] args, CancellationToken cancellationToken = default)
        {
            SendCalls++;
            if (ThrowOnSend)
            {
                throw new InvalidOperationException("Synthetic SignalR delivery failure.");
            }

            return Task.CompletedTask;
        }
    }

    private sealed class TestHubContext(RecordingClientProxy proxy)
        : IHubContext<MobileNotificationsHub>
    {
        public IHubClients Clients { get; } = new TestHubClients(proxy);
        public IGroupManager Groups { get; } = new TestGroupManager();
    }

    private sealed class TestHubClients(IClientProxy proxy) : IHubClients
    {
        public IClientProxy All => proxy;
        public IClientProxy AllExcept(IReadOnlyList<string> excludedConnectionIds) => proxy;
        public IClientProxy Client(string connectionId) => proxy;
        public IClientProxy Clients(IReadOnlyList<string> connectionIds) => proxy;
        public IClientProxy Group(string groupName) => proxy;
        public IClientProxy GroupExcept(string groupName, IReadOnlyList<string> excludedConnectionIds) => proxy;
        public IClientProxy Groups(IReadOnlyList<string> groupNames) => proxy;
        public IClientProxy User(string userId) => proxy;
        public IClientProxy Users(IReadOnlyList<string> userIds) => proxy;
    }

    private sealed class TestGroupManager : IGroupManager
    {
        public Task AddToGroupAsync(string connectionId, string groupName, CancellationToken cancellationToken = default) => Task.CompletedTask;
        public Task RemoveFromGroupAsync(string connectionId, string groupName, CancellationToken cancellationToken = default) => Task.CompletedTask;
    }
}
