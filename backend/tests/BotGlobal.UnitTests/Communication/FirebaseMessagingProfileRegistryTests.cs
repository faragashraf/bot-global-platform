using BotGlobal.Communication.Application.MobileNotifications.Fcm;
using BotGlobal.Communication.Application.MobileNotifications.Push;
using BotGlobal.Contracts.Notifications;
using FirebaseAdmin.Messaging;
using Microsoft.Extensions.Logging.Abstractions;
using RuntimeFcmOptions = BotGlobal.Communication.Application.MobileNotifications.Fcm.FcmOptions;

namespace BotGlobal.UnitTests.Communication;

public sealed class FirebaseMessagingProfileRegistryTests
{
    [Fact]
    public void Legacy_single_profile_configuration_remains_supported()
    {
        var applicationId = Guid.NewGuid();

        var profiles = FirebaseProfileConfiguration.Create(
            new RuntimeFcmOptions
            {
                Enabled = true,
                ApplicationId = applicationId,
                ConfigurationReference = "legacy-firebase",
                ProjectId = "legacy-project",
                CredentialPath = "/test/legacy-credential.json"
            });

        var profile = Assert.Single(profiles);
        Assert.True(profile.Enabled);
        Assert.Equal(applicationId, profile.ApplicationId);
        Assert.Equal("legacy-firebase", profile.ConfigurationReference);
        Assert.Equal("legacy-project", profile.ProjectId);
    }

    [Fact]
    public void Disabled_legacy_configuration_remains_inactive_even_with_stale_values()
    {
        var profiles = FirebaseProfileConfiguration.Create(
            new RuntimeFcmOptions
            {
                Enabled = false,
                ProjectId = "stale-disabled-project",
                CredentialPath = "/stale/disabled-credential.json"
            });

        Assert.Empty(profiles);
    }

    [Fact]
    public void Two_profiles_coexist_and_resolve_their_own_clients()
    {
        var nqrb = Guid.NewGuid();
        var enpo = Guid.NewGuid();
        var nqrbClient = new RecordingMessagingClient("nqrb-message");
        var enpoClient = new RecordingMessagingClient("enpo-message");

        using var registry = Registry(
            Profile(nqrb, "nqrb-firebase", "nqrb-project", nqrbClient),
            Profile(enpo, "enpo-firebase", "enpo-project", enpoClient));

        var nqrbResolution = registry.Resolve(
            Route(nqrb, "nqrb-firebase", "nqrb-project", "com.botglobal.nqrb"));
        var enpoResolution = registry.Resolve(
            Route(enpo, "enpo-firebase", "enpo-project", "com.botglobal.enpo"));

        Assert.Equal(FirebaseMessagingResolutionKind.Ready, nqrbResolution.Kind);
        Assert.Same(nqrbClient, nqrbResolution.Messaging);
        Assert.Equal(FirebaseMessagingResolutionKind.Ready, enpoResolution.Kind);
        Assert.Same(enpoClient, enpoResolution.Messaging);
    }

    [Fact]
    public void Cross_application_profile_resolution_fails_closed()
    {
        var nqrb = Guid.NewGuid();
        var enpo = Guid.NewGuid();

        using var registry = Registry(
            Profile(nqrb, "nqrb-firebase", "nqrb-project"),
            Profile(enpo, "enpo-firebase", "enpo-project"));

        var resolution = registry.Resolve(
            Route(nqrb, "enpo-firebase", "enpo-project", "com.botglobal.nqrb"));

        Assert.Equal(
            FirebaseMessagingResolutionKind.ScopeMismatch,
            resolution.Kind);
        Assert.Null(resolution.Messaging);
    }

    [Fact]
    public void Unknown_application_profile_fails_closed()
    {
        using var registry = Registry(
            Profile(Guid.NewGuid(), "nqrb-firebase", "nqrb-project"));

        var resolution = registry.Resolve(
            Route(
                Guid.NewGuid(),
                "unknown-firebase",
                "unknown-project",
                "com.botglobal.unknown"));

        Assert.Equal(FirebaseMessagingResolutionKind.Missing, resolution.Kind);
        Assert.Null(resolution.Messaging);
    }

    [Fact]
    public void Unknown_configuration_reference_fails_closed()
    {
        var applicationId = Guid.NewGuid();
        using var registry = Registry(
            Profile(applicationId, "nqrb-firebase", "nqrb-project"));

        var resolution = registry.Resolve(
            Route(applicationId, "unknown-firebase", "nqrb-project", "com.botglobal.nqrb"));

        Assert.Equal(
            FirebaseMessagingResolutionKind.ScopeMismatch,
            resolution.Kind);
    }

    [Fact]
    public void Duplicate_configuration_reference_is_rejected()
    {
        var options = Options(
            ProfileOptions(Guid.NewGuid(), "shared-reference", "project-a"),
            ProfileOptions(Guid.NewGuid(), "shared-reference", "project-b"));

        var exception = Assert.Throws<InvalidOperationException>(
            () => FirebaseProfileConfiguration.Create(options));

        Assert.Equal(
            "Firebase profile configuration reference is ambiguous.",
            exception.Message);
    }

    [Fact]
    public void Duplicate_application_profile_is_rejected()
    {
        var applicationId = Guid.NewGuid();
        var options = Options(
            ProfileOptions(applicationId, "firebase-a", "project-a"),
            ProfileOptions(applicationId, "firebase-b", "project-b"));

        var exception = Assert.Throws<InvalidOperationException>(
            () => FirebaseProfileConfiguration.Create(options));

        Assert.Equal(
            "Firebase profile application configuration is ambiguous.",
            exception.Message);
    }

    [Fact]
    public void Disabled_profile_cannot_resolve()
    {
        var applicationId = Guid.NewGuid();
        using var registry = Registry(
            Profile(
                applicationId,
                "nqrb-firebase",
                "nqrb-project",
                enabled: false));

        var resolution = registry.Resolve(
            Route(applicationId, "nqrb-firebase", "nqrb-project", "com.botglobal.nqrb"));

        Assert.Equal(FirebaseMessagingResolutionKind.Disabled, resolution.Kind);
        Assert.Null(resolution.Messaging);
    }

    [Fact]
    public void Project_mismatch_fails_closed()
    {
        var applicationId = Guid.NewGuid();
        using var registry = Registry(
            Profile(applicationId, "nqrb-firebase", "nqrb-project"));

        var resolution = registry.Resolve(
            Route(applicationId, "nqrb-firebase", "different-project", "com.botglobal.nqrb"));

        Assert.Equal(
            FirebaseMessagingResolutionKind.ScopeMismatch,
            resolution.Kind);
    }

    [Fact]
    public void Missing_credential_fails_closed_without_disclosing_path()
    {
        var sensitivePath = Path.Combine(
            Path.GetTempPath(),
            $"private-{Guid.NewGuid():N}.json");

        var exception = Assert.Throws<InvalidOperationException>(
            () => FirebaseAdminFactory.CreateRegistry(
                Options(ProfileOptions(
                    Guid.NewGuid(),
                    "nqrb-firebase",
                    "nqrb-project",
                    sensitivePath))));

        Assert.Equal(
            "A configured Firebase credential file does not exist.",
            exception.Message);
        Assert.DoesNotContain(sensitivePath, exception.ToString(), StringComparison.Ordinal);
    }

    [Fact]
    public void Invalid_credential_fails_closed_without_disclosing_content_or_path()
    {
        var sensitiveMarker = $"private-{Guid.NewGuid():N}";
        var credentialPath = Path.Combine(
            Path.GetTempPath(),
            $"firebase-invalid-{Guid.NewGuid():N}.json");

        try
        {
            File.WriteAllText(credentialPath, sensitiveMarker);

            var exception = Assert.Throws<InvalidOperationException>(
                () => FirebaseAdminFactory.CreateRegistry(
                    Options(ProfileOptions(
                        Guid.NewGuid(),
                        "nqrb-firebase",
                        "nqrb-project",
                        credentialPath))));

            Assert.Equal(
                "A configured Firebase credential could not be loaded.",
                exception.Message);
            Assert.DoesNotContain(credentialPath, exception.ToString(), StringComparison.Ordinal);
            Assert.DoesNotContain(sensitiveMarker, exception.ToString(), StringComparison.Ordinal);
        }
        finally
        {
            File.Delete(credentialPath);
        }
    }

    [Fact]
    public async Task Sender_dispatches_only_through_the_resolved_profile()
    {
        var nqrb = Guid.NewGuid();
        var enpo = Guid.NewGuid();
        var nqrbClient = new RecordingMessagingClient("nqrb-message");
        var enpoClient = new RecordingMessagingClient("enpo-message");
        using var registry = Registry(
            Profile(nqrb, "nqrb-firebase", "nqrb-project", nqrbClient),
            Profile(enpo, "enpo-firebase", "enpo-project", enpoClient));
        var sender = new FirebaseAdminFcmPushSender(
            registry,
            NullLogger<FirebaseAdminFcmPushSender>.Instance);

        var result = await sender.SendAsync(
            Route(nqrb, "nqrb-firebase", "nqrb-project", "com.botglobal.nqrb"),
            PushMessage(),
            CancellationToken.None);

        Assert.True(result.Accepted);
        Assert.Equal("nqrb-message", result.MessageId);
        Assert.Equal(1, nqrbClient.Calls);
        Assert.Equal(0, enpoClient.Calls);
    }

    [Fact]
    public async Task Sender_never_falls_back_to_another_application_profile()
    {
        var nqrb = Guid.NewGuid();
        var enpoClient = new RecordingMessagingClient("enpo-message");
        using var registry = Registry(
            Profile(Guid.NewGuid(), "enpo-firebase", "enpo-project", enpoClient));
        var sender = new FirebaseAdminFcmPushSender(
            registry,
            NullLogger<FirebaseAdminFcmPushSender>.Instance);

        var result = await sender.SendAsync(
            Route(nqrb, "nqrb-firebase", "nqrb-project", "com.botglobal.nqrb"),
            PushMessage(),
            CancellationToken.None);

        Assert.False(result.Accepted);
        Assert.True(result.IsPermanentFailure);
        Assert.Equal("fcm-runtime-missing", result.SafeErrorCode);
        Assert.Equal(0, enpoClient.Calls);
    }

    private static RuntimeFcmOptions Options(params FcmProfileOptions[] profiles) =>
        new()
        {
            Profiles = profiles.ToList()
        };

    private static FcmProfileOptions ProfileOptions(
        Guid applicationId,
        string configurationReference,
        string projectId,
        string credentialPath = "/test/firebase-credential.json") =>
        new()
        {
            Enabled = true,
            ApplicationId = applicationId,
            ConfigurationReference = configurationReference,
            ProjectId = projectId,
            CredentialPath = credentialPath
        };

    private static FirebaseMessagingProfileRegistry Registry(
        params FirebaseMessagingProfile[] profiles) =>
        new(profiles);

    private static FirebaseMessagingProfile Profile(
        Guid applicationId,
        string configurationReference,
        string projectId,
        IFirebaseMessagingClient? client = null,
        bool enabled = true) =>
        new(
            new FirebaseProfileConfiguration(
                enabled,
                applicationId,
                configurationReference,
                projectId,
                enabled ? "/test/firebase-credential.json" : string.Empty),
            enabled ? client ?? new RecordingMessagingClient("message") : null,
            null);

    private static ResolvedApplicationPushProvider Route(
        Guid applicationId,
        string configurationReference,
        string projectId,
        string packageName) =>
        new(
            new NotificationApplicationContext(applicationId),
            PushProviderNames.FirebaseCloudMessaging,
            configurationReference,
            projectId,
            packageName,
            null);

    private static FcmPushMessage PushMessage() =>
        new(
            "test-registration-token",
            "Title",
            "Body",
            new Dictionary<string, string>
            {
                ["type"] = "test"
            },
            TimeSpan.FromMinutes(5));

    private sealed class RecordingMessagingClient(string messageId)
        : IFirebaseMessagingClient
    {
        public int Calls { get; private set; }

        public Task<string> SendAsync(
            Message message,
            CancellationToken cancellationToken)
        {
            Calls++;
            return Task.FromResult(messageId);
        }
    }
}
