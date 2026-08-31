using FirebaseAdmin;
using FirebaseAdmin.Messaging;
using Google.Apis.Auth.OAuth2;

namespace BotGlobal.Communication.Application.MobileNotifications.Fcm;

internal static class FirebaseAdminFactory
{
    public static FirebaseMessagingProfileRegistry CreateRegistry(
        FcmOptions options)
    {
        ArgumentNullException.ThrowIfNull(options);

        var configurations =
            FirebaseProfileConfiguration.Create(options);
        var profiles =
            new List<FirebaseMessagingProfile>();

        try
        {
            foreach (var configuration in configurations)
            {
                profiles.Add(
                    configuration.Enabled
                        ? CreateEnabledProfile(configuration)
                        : new FirebaseMessagingProfile(
                            configuration,
                            null,
                            null));
            }

            return new FirebaseMessagingProfileRegistry(profiles);
        }
        catch
        {
            foreach (var profile in profiles)
            {
                profile.Application?.Delete();
            }

            throw;
        }
    }

    private static FirebaseMessagingProfile CreateEnabledProfile(
        FirebaseProfileConfiguration configuration)
    {
        if (!File.Exists(configuration.CredentialPath))
        {
            throw new InvalidOperationException(
                "A configured Firebase credential file does not exist.");
        }

        ServiceAccountCredential serviceAccountCredential;

        try
        {
            serviceAccountCredential =
                CredentialFactory
                    .FromFile<ServiceAccountCredential>(
                        configuration.CredentialPath);
        }
        catch (Exception exception)
            when (exception is not OutOfMemoryException)
        {
            throw new InvalidOperationException(
                "A configured Firebase credential could not be loaded.");
        }

        if (!string.Equals(
                serviceAccountCredential.ProjectId,
                configuration.ProjectId,
                StringComparison.Ordinal))
        {
            throw new InvalidOperationException(
                "A configured Firebase credential does not match its project.");
        }

        var credential =
            serviceAccountCredential.ToGoogleCredential();

        var appName =
            $"botglobal-{configuration.ApplicationId:N}-{configuration.ProjectId}";

        if (FirebaseApp.GetInstance(appName) is not null)
        {
            throw new InvalidOperationException(
                "A configured Firebase application name is already in use.");
        }

        var app = FirebaseApp.Create(
            new AppOptions
            {
                Credential = credential,
                ProjectId = configuration.ProjectId
            },
            appName);

        return new FirebaseMessagingProfile(
            configuration,
            new FirebaseAdminMessagingClient(
                FirebaseMessaging.GetMessaging(app)),
            app);
    }
}
