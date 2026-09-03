using BotGlobal.Communication.Application.MobileNotifications;
using BotGlobal.Communication.Contracts.MobileNotifications;
using BotGlobal.Contracts.Calling;
using BotGlobal.Contracts.Notifications;

namespace BotGlobal.UnitTests.Communication;

public sealed class IncomingCallNotificationDispatcherTests
{
    [Fact]
    public async Task Dispatch_uses_the_resolved_application_scope_and_display_name()
    {
        var application = new PlatformClientDescriptor(
            Guid.NewGuid(),
            "product-blue",
            "Product Blue",
            true);
        var resolver = new RecordingApplicationResolver(application);
        var notifications = new RecordingNotificationService();
        var dispatcher = new IncomingCallNotificationDispatcher(resolver, notifications);
        var callId = Guid.NewGuid();
        var expiresAtUtc = DateTimeOffset.Parse("2026-08-31T12:00:45Z");

        await dispatcher.DispatchAsync(
            new IncomingCallNotification(
                " product-blue ",
                "callee-subject",
                callId,
                IncomingCallNotificationKind.Offered,
                "Caller",
                expiresAtUtc),
            CancellationToken.None);

        Assert.Equal("product-blue", resolver.RequestedClientKey);
        Assert.Equal(application.PlatformClientId, notifications.PlatformClientId);
        var request = Assert.IsType<SendMobileNotificationRequest>(notifications.Request);
        Assert.Equal(application.DisplayName, request.TitleAr);
        Assert.Equal(application.DisplayName, request.TitleEn);
        Assert.Equal("Caller", request.BodyAr);
        Assert.Equal("Caller", request.BodyEn);
        Assert.Equal("incoming_call", request.Type);
        Assert.Equal(MobileNotificationPriority.High, request.Priority);
        Assert.Equal(callId.ToString("D"), request.Data!["callId"]);
        Assert.Equal(expiresAtUtc.ToString("O"), request.Data["expiresAtUtc"]);
    }

    [Fact]
    public async Task Dispatch_rejects_a_descriptor_from_another_application()
    {
        var resolver = new RecordingApplicationResolver(
            new PlatformClientDescriptor(Guid.NewGuid(), "other-app", "Other App", true));
        var notifications = new RecordingNotificationService();
        var dispatcher = new IncomingCallNotificationDispatcher(resolver, notifications);

        var error = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            dispatcher.DispatchAsync(Notification("product-blue"), CancellationToken.None));

        Assert.Equal("incoming_call_application_unavailable", error.Message);
        Assert.Null(notifications.Request);
    }

    [Fact]
    public async Task Dispatch_rejects_an_inactive_application()
    {
        var resolver = new RecordingApplicationResolver(
            new PlatformClientDescriptor(Guid.NewGuid(), "product-blue", "Product Blue", false));
        var notifications = new RecordingNotificationService();
        var dispatcher = new IncomingCallNotificationDispatcher(resolver, notifications);

        var error = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            dispatcher.DispatchAsync(Notification("product-blue"), CancellationToken.None));

        Assert.Equal("incoming_call_application_unavailable", error.Message);
        Assert.Null(notifications.Request);
    }

    private static IncomingCallNotification Notification(string applicationKey) =>
        new(
            applicationKey,
            "callee-subject",
            Guid.NewGuid(),
            IncomingCallNotificationKind.Offered,
            "Caller",
            DateTimeOffset.Parse("2026-08-31T12:00:45Z"));

    private sealed class RecordingApplicationResolver(PlatformClientDescriptor? result)
        : IPlatformClientApplicationResolver
    {
        public string? RequestedClientKey { get; private set; }

        public Task<PlatformClientDescriptor?> FindByClientKeyAsync(
            string clientKey,
            CancellationToken cancellationToken)
        {
            RequestedClientKey = clientKey;
            return Task.FromResult(result);
        }
    }

    private sealed class RecordingNotificationService : IMobileNotificationService
    {
        public Guid? PlatformClientId { get; private set; }
        public SendMobileNotificationRequest? Request { get; private set; }

        public Task<SendMobileNotificationResponse> SendAsync(
            Guid platformClientId,
            SendMobileNotificationRequest request,
            CancellationToken cancellationToken)
        {
            PlatformClientId = platformClientId;
            Request = request;
            return Task.FromResult(new SendMobileNotificationResponse(
                "notification-id",
                request.RecipientExternalSubjectId,
                1,
                "accepted"));
        }
    }
}
