using BotGlobal.Communication.Application.MobileNotifications.Fcm;
using BotGlobal.Contracts.Notifications;
using FirebaseAdmin.Messaging;

namespace BotGlobal.Communication.Application.MobileNotifications.Push;

internal enum ApplicationPushDispatchKind
{
    Accepted = 1,
    ProviderDisabled = 2,
    MissingConfiguration = 3,
    RuntimeUnavailable = 4,
    TransientFailure = 5,
    PermanentFailure = 6
}

internal sealed record ApplicationPushMessage(
    NotificationApplicationContext Application,
    string Provider,
    string RegistrationToken,
    string Title,
    string Body,
    IReadOnlyDictionary<string, string> Data,
    TimeSpan TimeToLive);

internal sealed record ApplicationPushDispatchResult(
    ApplicationPushDispatchKind Kind,
    string? SafeErrorCode = null);

internal interface IApplicationPushNotificationDispatcher
{
    Task<ApplicationPushDispatchResult> DispatchAsync(
        ApplicationPushMessage message,
        CancellationToken cancellationToken);
}

internal sealed class ApplicationPushNotificationDispatcher(
    IApplicationPushProviderResolver providers,
    IFcmPushSender fcm)
    : IApplicationPushNotificationDispatcher
{
    public async Task<ApplicationPushDispatchResult> DispatchAsync(
        ApplicationPushMessage message,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(message);

        var resolution = providers.Resolve(
            message.Application,
            message.Provider);

        if (resolution.Kind
            == ApplicationPushProviderResolutionKind.Missing)
        {
            return new ApplicationPushDispatchResult(
                ApplicationPushDispatchKind.MissingConfiguration,
                "push-provider-configuration-missing");
        }

        if (resolution.Kind
            == ApplicationPushProviderResolutionKind.Disabled)
        {
            return new ApplicationPushDispatchResult(
                ApplicationPushDispatchKind.ProviderDisabled,
                "push-provider-disabled");
        }

        var configuration = resolution.Configuration!;
        if (!string.Equals(
                configuration.Provider,
                PushProviderNames.FirebaseCloudMessaging,
                StringComparison.Ordinal))
        {
            return new ApplicationPushDispatchResult(
                ApplicationPushDispatchKind.RuntimeUnavailable,
                "push-provider-runtime-unavailable");
        }

        try
        {
            var result = await fcm.SendAsync(
                configuration,
                new FcmPushMessage(
                    message.RegistrationToken,
                    message.Title,
                    message.Body,
                    message.Data,
                    message.TimeToLive),
                cancellationToken);

            if (result.Accepted)
            {
                return new ApplicationPushDispatchResult(
                    ApplicationPushDispatchKind.Accepted);
            }

            return new ApplicationPushDispatchResult(
                result.IsPermanentFailure
                    ? ApplicationPushDispatchKind.PermanentFailure
                    : ApplicationPushDispatchKind.RuntimeUnavailable,
                result.SafeErrorCode ?? "push-provider-runtime-unavailable");
        }
        catch (OperationCanceledException)
            when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (FirebaseMessagingException exception)
        {
            var code = SanitizeFirebaseCode(exception);

            return new ApplicationPushDispatchResult(
                IsPermanentFirebaseFailure(exception)
                    ? ApplicationPushDispatchKind.PermanentFailure
                    : ApplicationPushDispatchKind.TransientFailure,
                code);
        }
        catch (ArgumentException)
        {
            return new ApplicationPushDispatchResult(
                ApplicationPushDispatchKind.PermanentFailure,
                "invalid-push-request");
        }
        catch (Exception)
        {
            return new ApplicationPushDispatchResult(
                ApplicationPushDispatchKind.TransientFailure,
                "push-provider-unavailable");
        }
    }

    private static bool IsPermanentFirebaseFailure(
        FirebaseMessagingException exception)
    {
        var code = exception.MessagingErrorCode?.ToString();

        return code is "InvalidArgument"
            or "SenderIdMismatch"
            or "ThirdPartyAuthError"
            or "Unregistered";
    }

    private static string SanitizeFirebaseCode(
        FirebaseMessagingException exception)
    {
        var code = exception.MessagingErrorCode?.ToString();

        return string.IsNullOrWhiteSpace(code)
            ? "fcm-error"
            : $"fcm-{code.ToLowerInvariant()}";
    }
}
