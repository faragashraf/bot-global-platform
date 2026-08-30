using FirebaseAdmin;
using FirebaseAdmin.Messaging;
using Google.Apis.Auth.OAuth2;
using Microsoft.Extensions.Options;

namespace BotGlobal.Communication.Application.MobileNotifications.Fcm;

internal static class FirebaseAdminFactory
{
    public static FirebaseMessaging CreateMessaging(
        FcmOptions options)
    {
        ArgumentNullException.ThrowIfNull(options);

        if (!options.Enabled)
        {
            throw new InvalidOperationException(
                "Firebase messaging is disabled.");
        }

        if (options.ApplicationId == Guid.Empty)
        {
            throw new InvalidOperationException(
                "Firebase ApplicationId is required.");
        }

        if (string.IsNullOrWhiteSpace(
                options.ConfigurationReference))
        {
            throw new InvalidOperationException(
                "Firebase ConfigurationReference is required.");
        }

        if (string.IsNullOrWhiteSpace(
                options.ProjectId))
        {
            throw new InvalidOperationException(
                "Firebase ProjectId is required.");
        }

        if (string.IsNullOrWhiteSpace(
                options.CredentialPath))
        {
            throw new InvalidOperationException(
                "Firebase CredentialPath is required.");
        }

        if (!File.Exists(
                options.CredentialPath))
        {
            throw new InvalidOperationException(
                "Firebase credential file does not exist.");
        }

        var credential =
            CredentialFactory
                .FromFile<ServiceAccountCredential>(
                    options.CredentialPath)
                .ToGoogleCredential();

        var appName =
            $"botglobal-{options.ApplicationId:N}-{options.ProjectId}";

        var app =
            FirebaseApp.GetInstance(
                appName);

        if (app is null)
        {
            app =
                FirebaseApp.Create(
                    new AppOptions
                    {
                        Credential = credential,
                        ProjectId =
                            options.ProjectId
                    },
                    appName);
        }

        return FirebaseMessaging.GetMessaging(
            app);
    }
}
