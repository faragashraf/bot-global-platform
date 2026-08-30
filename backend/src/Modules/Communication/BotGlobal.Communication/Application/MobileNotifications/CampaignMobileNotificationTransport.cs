using BotGlobal.Communication.Application.MobileNotifications.Fcm;
using BotGlobal.Communication.Contracts.MobileNotifications;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Notifications;
using FirebaseAdmin.Messaging;

namespace BotGlobal.Communication.Application.MobileNotifications;

internal sealed class CampaignMobileNotificationTransport(
    SignalRMobileNotificationDelivery signalR,
    IMobileNotificationConnectionRegistry connections,
    IMobilePushDestinationResolver pushDestinations,
    IMobileBroadcastAudienceReader audienceReader,
    IFcmPushSender fcm)
    : IMobileNotificationTransport
{
    public async Task<MobileNotificationTransportOutcome> DispatchAsync(
        MobileNotificationTransportRequest request,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(request);

        var deviceState = await audienceReader
            .GetCurrentDeviceStateAsync(
                request.PlatformClientId,
                request.MobileDeviceId,
                cancellationToken);

        if (!deviceState.ExistsForPlatformClient
            || deviceState.IsRevoked)
        {
            return new MobileNotificationTransportOutcome(
                MobileNotificationTransportOutcomeKind.DeviceRevoked,
                "device-revoked-or-unavailable");
        }

        var priority = request.Priority == (int)MobileNotificationPriority.High
            ? MobileNotificationPriority.High
            : MobileNotificationPriority.Normal;

        var envelope = new MobileNotificationEnvelope(
            request.NotificationId,
            "campaign",
            request.TitleAr,
            request.TitleEn,
            request.BodyAr,
            request.BodyEn,
            request.Type,
            priority,
            DateTimeOffset.UtcNow);

        try
        {
            if (connections.IsConnected(request.MobileDeviceId))
            {
                await signalR.DeliverAsync(
                    envelope,
                    [new MobileRecipientDevice(
                        request.MobileDeviceId,
                        request.InstallationId,
                        request.Platform,
                        request.DeviceName)],
                    cancellationToken);

                return new MobileNotificationTransportOutcome(
                    MobileNotificationTransportOutcomeKind.SignalRDispatched);
            }

            var destination = await pushDestinations.ResolveActiveAsync(
                request.MobileDeviceId,
                "fcm",
                cancellationToken);

            if (destination is null)
            {
                return new MobileNotificationTransportOutcome(
                    MobileNotificationTransportOutcomeKind.NoAvailableRoute,
                    "no-active-route");
            }

            var pushData = new Dictionary<string, string>
            {
                ["notificationId"] = request.NotificationId,
                ["type"] = request.Type,
                ["titleAr"] = request.TitleAr,
                ["titleEn"] = request.TitleEn,
                ["bodyAr"] = request.BodyAr,
                ["bodyEn"] = request.BodyEn,
                ["priority"] = priority.ToString()
            };

            var actionUrl = ResolveActionUrl(
                request.BodyAr,
                request.BodyEn);

            if (!string.IsNullOrWhiteSpace(actionUrl))
            {
                pushData["actionUrl"] = actionUrl;
            }

            var pushResult = await fcm.SendAsync(
                new FcmPushMessage(
                    destination.RegistrationToken,
                    request.TitleAr,
                    request.BodyAr,
                    pushData,
                    request.TimeToLive),
                cancellationToken);

            return pushResult.Accepted
                ? new MobileNotificationTransportOutcome(
                    MobileNotificationTransportOutcomeKind.FcmAccepted)
                : new MobileNotificationTransportOutcome(
                    MobileNotificationTransportOutcomeKind.TransientFailure,
                    "fcm-not-accepted");
        }
        catch (OperationCanceledException)
            when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (FirebaseMessagingException exception)
        {
            var code = SanitizeFirebaseCode(exception);

            return new MobileNotificationTransportOutcome(
                IsPermanentFirebaseFailure(exception)
                    ? MobileNotificationTransportOutcomeKind.PermanentFailure
                    : MobileNotificationTransportOutcomeKind.TransientFailure,
                code);
        }
        catch (ArgumentException)
        {
            return new MobileNotificationTransportOutcome(
                MobileNotificationTransportOutcomeKind.PermanentFailure,
                "invalid-transport-request");
        }
        catch (Exception)
        {
            return new MobileNotificationTransportOutcome(
                MobileNotificationTransportOutcomeKind.TransientFailure,
                "transport-unavailable");
        }
    }

    private static string? ResolveActionUrl(
        params string?[] bodies)
    {
        foreach (var body in bodies)
        {
            if (string.IsNullOrWhiteSpace(body))
            {
                continue;
            }

            var match = System.Text.RegularExpressions.Regex.Match(
                body,
                @"https?://\S+",
                System.Text.RegularExpressions.RegexOptions.IgnoreCase,
                TimeSpan.FromSeconds(1));

            if (match.Success)
            {
                return match.Value;
            }
        }

        return null;
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

        if (string.IsNullOrWhiteSpace(code))
        {
            return "fcm-error";
        }

        return $"fcm-{code.ToLowerInvariant()}";
    }
}
