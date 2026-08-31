using BotGlobal.Communication.Application.MobileNotifications.Push;
using FirebaseAdmin;
using FirebaseAdmin.Messaging;
using Microsoft.Extensions.Hosting;

namespace BotGlobal.Communication.Application.MobileNotifications.Fcm;

internal sealed record FirebaseProfileConfiguration(
    bool Enabled,
    Guid ApplicationId,
    string ConfigurationReference,
    string ProjectId,
    string CredentialPath)
{
    public static IReadOnlyList<FirebaseProfileConfiguration> Create(
        FcmOptions options)
    {
        ArgumentNullException.ThrowIfNull(options);

        var profiles = options.Profiles
            .Select(profile => new FirebaseProfileConfiguration(
                profile.Enabled,
                profile.ApplicationId,
                Normalize(profile.ConfigurationReference),
                Normalize(profile.ProjectId),
                Normalize(profile.CredentialPath)))
            .ToList();

        if (HasLegacyConfiguration(options))
        {
            profiles.Add(new FirebaseProfileConfiguration(
                options.Enabled,
                options.ApplicationId,
                Normalize(options.ConfigurationReference),
                Normalize(options.ProjectId),
                Normalize(options.CredentialPath)));
        }

        Validate(profiles);
        return profiles;
    }

    private static bool HasLegacyConfiguration(FcmOptions options) =>
        options.Enabled;

    private static void Validate(
        IReadOnlyCollection<FirebaseProfileConfiguration> profiles)
    {
        var applicationIds = new HashSet<Guid>();
        var references = new HashSet<string>(StringComparer.Ordinal);

        foreach (var profile in profiles)
        {
            if (profile.ApplicationId == Guid.Empty
                || string.IsNullOrWhiteSpace(profile.ConfigurationReference)
                || string.IsNullOrWhiteSpace(profile.ProjectId)
                || (profile.Enabled
                    && string.IsNullOrWhiteSpace(profile.CredentialPath)))
            {
                throw new InvalidOperationException(
                    "Firebase profile configuration is incomplete.");
            }

            if (!applicationIds.Add(profile.ApplicationId))
            {
                throw new InvalidOperationException(
                    "Firebase profile application configuration is ambiguous.");
            }

            if (!references.Add(profile.ConfigurationReference))
            {
                throw new InvalidOperationException(
                    "Firebase profile configuration reference is ambiguous.");
            }
        }
    }

    private static string Normalize(string? value) =>
        value?.Trim() ?? string.Empty;
}

internal interface IFirebaseMessagingClient
{
    Task<string> SendAsync(
        Message message,
        CancellationToken cancellationToken);
}

internal sealed class FirebaseAdminMessagingClient(
    FirebaseMessaging messaging)
    : IFirebaseMessagingClient
{
    public Task<string> SendAsync(
        Message message,
        CancellationToken cancellationToken) =>
        messaging.SendAsync(message, cancellationToken);
}

internal sealed record FirebaseMessagingProfile(
    FirebaseProfileConfiguration Configuration,
    IFirebaseMessagingClient? Messaging,
    FirebaseApp? Application);

internal enum FirebaseMessagingResolutionKind
{
    Ready = 1,
    Missing = 2,
    Disabled = 3,
    ScopeMismatch = 4
}

internal sealed record FirebaseMessagingResolution(
    FirebaseMessagingResolutionKind Kind,
    IFirebaseMessagingClient? Messaging);

internal interface IFirebaseMessagingResolver
{
    FirebaseMessagingResolution Resolve(
        ResolvedApplicationPushProvider configuration);
}

internal sealed class FirebaseMessagingInitializationService(
    IFirebaseMessagingResolver resolver)
    : IHostedService
{
    public Task StartAsync(CancellationToken cancellationToken)
    {
        _ = resolver;
        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken) =>
        Task.CompletedTask;
}

internal sealed class FirebaseMessagingProfileRegistry :
    IFirebaseMessagingResolver,
    IDisposable
{
    private readonly IReadOnlyDictionary<Guid, FirebaseMessagingProfile>
        profilesByApplication;
    private bool disposed;

    public FirebaseMessagingProfileRegistry(
        IEnumerable<FirebaseMessagingProfile> profiles)
    {
        ArgumentNullException.ThrowIfNull(profiles);

        var profileList = profiles.ToList();
        FirebaseProfileConfiguration.Create(
            new FcmOptions
            {
                Profiles = profileList
                    .Select(profile => new FcmProfileOptions
                    {
                        Enabled = profile.Configuration.Enabled,
                        ApplicationId = profile.Configuration.ApplicationId,
                        ConfigurationReference = profile.Configuration.ConfigurationReference,
                        ProjectId = profile.Configuration.ProjectId,
                        CredentialPath = profile.Configuration.CredentialPath
                    })
                    .ToList()
            });

        profilesByApplication = profileList.ToDictionary(
            profile => profile.Configuration.ApplicationId);
    }

    public FirebaseMessagingResolution Resolve(
        ResolvedApplicationPushProvider configuration)
    {
        ArgumentNullException.ThrowIfNull(configuration);
        ObjectDisposedException.ThrowIf(disposed, this);

        if (!profilesByApplication.TryGetValue(
                configuration.Application.ApplicationId,
                out var profile))
        {
            return new FirebaseMessagingResolution(
                FirebaseMessagingResolutionKind.Missing,
                null);
        }

        if (!string.Equals(
                configuration.ConfigurationReference,
                profile.Configuration.ConfigurationReference,
                StringComparison.Ordinal)
            || !string.Equals(
                configuration.FirebaseProjectId,
                profile.Configuration.ProjectId,
                StringComparison.Ordinal)
            || string.IsNullOrWhiteSpace(
                configuration.AndroidPackageName))
        {
            return new FirebaseMessagingResolution(
                FirebaseMessagingResolutionKind.ScopeMismatch,
                null);
        }

        if (!profile.Configuration.Enabled)
        {
            return new FirebaseMessagingResolution(
                FirebaseMessagingResolutionKind.Disabled,
                null);
        }

        return new FirebaseMessagingResolution(
            FirebaseMessagingResolutionKind.Ready,
            profile.Messaging
            ?? throw new InvalidOperationException(
                "Enabled Firebase profile has no messaging client."));
    }

    public void Dispose()
    {
        if (disposed)
        {
            return;
        }

        disposed = true;

        foreach (var profile in profilesByApplication.Values)
        {
            profile.Application?.Delete();
        }
    }
}
