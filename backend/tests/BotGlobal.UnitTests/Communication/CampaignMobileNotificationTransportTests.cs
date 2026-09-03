using BotGlobal.Communication.Application.MobileNotifications;
using BotGlobal.Communication.Application.MobileNotifications.Push;
using BotGlobal.Communication.Contracts.MobileNotifications;
using BotGlobal.Communication.Hubs;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Notifications;
using Microsoft.AspNetCore.SignalR;

namespace BotGlobal.UnitTests.Communication;

public sealed class CampaignMobileNotificationTransportTests
{
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
        public Task<ApplicationPushDispatchResult> DispatchAsync(
            ApplicationPushMessage message,
            CancellationToken cancellationToken)
        {
            Calls++;
            LastMessage = message;
            return Task.FromResult(
                result ?? new ApplicationPushDispatchResult(
                    ApplicationPushDispatchKind.Accepted,
                    ProviderMessageId: "provider-message-id"));
        }
    }

    private sealed class RecordingPushInvalidator
        : IMobilePushDestinationInvalidator
    {
        public Guid? ApplicationId { get; private set; }
        public Guid? DeviceId { get; private set; }
        public string? Provider { get; private set; }

        public Task InvalidateAsync(
            NotificationApplicationContext application,
            Guid deviceId,
            string provider,
            string safeReason,
            CancellationToken cancellationToken)
        {
            ApplicationId = application.ApplicationId;
            DeviceId = deviceId;
            Provider = provider;
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
