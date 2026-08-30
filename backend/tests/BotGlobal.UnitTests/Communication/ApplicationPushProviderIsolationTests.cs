using BotGlobal.Communication.Application.MobileNotifications.Fcm;
using BotGlobal.Communication.Application.MobileNotifications.Push;
using BotGlobal.Contracts.Notifications;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Communication;

public sealed class ApplicationPushProviderIsolationTests
{
    [Fact]
    public void Resolver_returns_app_a_provider_for_app_a()
    {
        var appA = Guid.NewGuid();
        var appB = Guid.NewGuid();
        var resolver = Resolver(
            Provider(appA, "firebase-a", "project-a", "com.botglobal.a"),
            Provider(appB, "firebase-b", "project-b", "com.botglobal.b"));

        var result = resolver.Resolve(
            new NotificationApplicationContext(appA),
            PushProviderNames.FirebaseCloudMessaging);

        Assert.Equal(
            ApplicationPushProviderResolutionKind.Ready,
            result.Kind);
        Assert.Equal(appA, result.Configuration!.Application.ApplicationId);
        Assert.Equal("firebase-a", result.Configuration.ConfigurationReference);
        Assert.Equal("project-a", result.Configuration.FirebaseProjectId);
        Assert.Equal("com.botglobal.a", result.Configuration.AndroidPackageName);
    }

    [Fact]
    public void Resolver_returns_app_b_provider_for_app_b()
    {
        var appA = Guid.NewGuid();
        var appB = Guid.NewGuid();
        var resolver = Resolver(
            Provider(appA, "firebase-a", "project-a", "com.botglobal.a"),
            Provider(appB, "firebase-b", "project-b", "com.botglobal.b"));

        var result = resolver.Resolve(
            new NotificationApplicationContext(appB),
            PushProviderNames.FirebaseCloudMessaging);

        Assert.Equal(
            ApplicationPushProviderResolutionKind.Ready,
            result.Kind);
        Assert.Equal(appB, result.Configuration!.Application.ApplicationId);
        Assert.Equal("firebase-b", result.Configuration.ConfigurationReference);
        Assert.Equal("project-b", result.Configuration.FirebaseProjectId);
        Assert.Equal("com.botglobal.b", result.Configuration.AndroidPackageName);
    }

    [Fact]
    public async Task App_a_notification_cannot_use_app_b_provider_configuration()
    {
        var appA = Guid.NewGuid();
        var appB = Guid.NewGuid();
        var fcm = new RecordingFcmSender();
        var dispatcher = new ApplicationPushNotificationDispatcher(
            Resolver(Provider(
                appB,
                "firebase-b",
                "project-b",
                "com.botglobal.b")),
            fcm);

        var result = await dispatcher.DispatchAsync(
            Message(appA),
            CancellationToken.None);

        Assert.Equal(
            ApplicationPushDispatchKind.MissingConfiguration,
            result.Kind);
        Assert.Equal("push-provider-configuration-missing", result.SafeErrorCode);
        Assert.Equal(0, fcm.Calls);
    }

    [Fact]
    public async Task Disabled_provider_is_handled_semantically_without_dispatch()
    {
        var applicationId = Guid.NewGuid();
        var fcm = new RecordingFcmSender();
        var dispatcher = new ApplicationPushNotificationDispatcher(
            Resolver(Provider(
                applicationId,
                "firebase-disabled",
                "project-disabled",
                "com.botglobal.disabled",
                enabled: false)),
            fcm);

        var result = await dispatcher.DispatchAsync(
            Message(applicationId),
            CancellationToken.None);

        Assert.Equal(
            ApplicationPushDispatchKind.ProviderDisabled,
            result.Kind);
        Assert.Equal("push-provider-disabled", result.SafeErrorCode);
        Assert.Equal(0, fcm.Calls);
    }

    [Fact]
    public async Task Missing_provider_configuration_fails_safely()
    {
        var fcm = new RecordingFcmSender();
        var dispatcher = new ApplicationPushNotificationDispatcher(
            Resolver(),
            fcm);

        var result = await dispatcher.DispatchAsync(
            Message(Guid.NewGuid()),
            CancellationToken.None);

        Assert.Equal(
            ApplicationPushDispatchKind.MissingConfiguration,
            result.Kind);
        Assert.Equal("push-provider-configuration-missing", result.SafeErrorCode);
        Assert.Equal(0, fcm.Calls);
    }

    [Fact]
    public async Task Resolved_provider_preserves_originating_application()
    {
        var applicationId = Guid.NewGuid();
        var fcm = new RecordingFcmSender();
        var dispatcher = new ApplicationPushNotificationDispatcher(
            Resolver(Provider(
                applicationId,
                "firebase-app",
                "project-app",
                "com.botglobal.app")),
            fcm);

        var result = await dispatcher.DispatchAsync(
            Message(applicationId),
            CancellationToken.None);

        Assert.Equal(ApplicationPushDispatchKind.Accepted, result.Kind);
        Assert.Equal("message-id", result.ProviderMessageId);
        Assert.Equal(1, fcm.Calls);
        Assert.Equal(
            applicationId,
            fcm.LastConfiguration!.Application.ApplicationId);
    }

    [Fact]
    public async Task Unknown_fcm_call_failure_is_ambiguous_not_retryable()
    {
        var applicationId = Guid.NewGuid();
        var dispatcher = new ApplicationPushNotificationDispatcher(
            Resolver(Provider(
                applicationId,
                "firebase-app",
                "project-app",
                "com.botglobal.app")),
            new ThrowingFcmSender());

        var result = await dispatcher.DispatchAsync(
            Message(applicationId),
            CancellationToken.None);

        Assert.Equal(ApplicationPushDispatchKind.Ambiguous, result.Kind);
        Assert.Equal("push-provider-outcome-unknown", result.SafeErrorCode);
    }

    private static ConfigurationApplicationPushProviderResolver Resolver(
        params ApplicationPushProviderConfiguration[] providers) =>
        new(Options.Create(
            new ApplicationPushProviderOptions
            {
                Providers = providers.ToList()
            }));

    private static ApplicationPushProviderConfiguration Provider(
        Guid applicationId,
        string configurationReference,
        string projectId,
        string packageName,
        bool enabled = true) =>
        new()
        {
            ApplicationId = applicationId,
            Provider = PushProviderNames.FirebaseCloudMessaging,
            Enabled = enabled,
            ConfigurationReference = configurationReference,
            FirebaseProjectId = projectId,
            AndroidPackageName = packageName
        };

    private static ApplicationPushMessage Message(Guid applicationId) =>
        new(
            new NotificationApplicationContext(applicationId),
            PushProviderNames.FirebaseCloudMessaging,
            "test-registration-token",
            "Title",
            "Body",
            new Dictionary<string, string>
            {
                ["type"] = "test"
            },
            TimeSpan.FromDays(1));

    private sealed class RecordingFcmSender : IFcmPushSender
    {
        public int Calls { get; private set; }

        public ResolvedApplicationPushProvider? LastConfiguration
        {
            get;
            private set;
        }

        public Task<FcmPushSendResult> SendAsync(
            ResolvedApplicationPushProvider configuration,
            FcmPushMessage message,
            CancellationToken cancellationToken)
        {
            Calls++;
            LastConfiguration = configuration;
            return Task.FromResult(
                new FcmPushSendResult(true, "message-id"));
        }
    }

    private sealed class ThrowingFcmSender : IFcmPushSender
    {
        public Task<FcmPushSendResult> SendAsync(
            ResolvedApplicationPushProvider configuration,
            FcmPushMessage message,
            CancellationToken cancellationToken) =>
            throw new InvalidOperationException("Synthetic unknown send outcome.");
    }
}
