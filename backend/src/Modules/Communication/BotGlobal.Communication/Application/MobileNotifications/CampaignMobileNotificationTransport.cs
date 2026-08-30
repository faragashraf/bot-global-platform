using BotGlobal.Communication.Application.MobileNotifications.Push;
using BotGlobal.Communication.Contracts.MobileNotifications;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Notifications;

namespace BotGlobal.Communication.Application.MobileNotifications;

internal sealed class CampaignMobileNotificationTransport(
    SignalRMobileNotificationDelivery signalR,
    IMobileNotificationConnectionRegistry connections,
    IMobilePushDestinationResolver pushDestinations,
    IMobileBroadcastAudienceReader audienceReader,
    IApplicationPushNotificationDispatcher push)
    : IMobileNotificationTransport
{
    public async Task<MobileNotificationTransportOutcome> DispatchAsync(
        MobileNotificationTransportRequest request,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(request);

        var deviceState = await audienceReader
            .GetCurrentDeviceStateAsync(
                request.Application,
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

        var sideEffectInvocationStarted = false;
        try
        {
            if (connections.IsConnected(request.MobileDeviceId))
            {
                sideEffectInvocationStarted = true;
                await signalR.DeliverAsync(
                    request.Application,
                    envelope,
                    [new MobileRecipientDevice(
                        request.MobileDeviceId,
                        request.InstallationId,
                        request.Platform,
                        request.DeviceName)],
                    cancellationToken);

                return new MobileNotificationTransportOutcome(
                    MobileNotificationTransportOutcomeKind.SignalRDispatched,
                    Transport: "SignalR");
            }

            var destination = await pushDestinations.ResolveActiveAsync(
                request.Application,
                request.MobileDeviceId,
                PushProviderNames.FirebaseCloudMessaging,
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
                ["deliveryAttemptId"] = request.DeliveryAttemptId.ToString("N"),
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

            sideEffectInvocationStarted = true;
            var pushResult = await push.DispatchAsync(
                new ApplicationPushMessage(
                    request.Application,
                    destination.Provider,
                    destination.RegistrationToken,
                    request.TitleAr,
                    request.BodyAr,
                    pushData,
                    request.TimeToLive),
                cancellationToken);

            return pushResult.Kind switch
            {
                ApplicationPushDispatchKind.Accepted =>
                    new MobileNotificationTransportOutcome(
                        MobileNotificationTransportOutcomeKind.FcmAccepted,
                        ProviderMessageId: pushResult.ProviderMessageId,
                        Transport: "Fcm"),

                ApplicationPushDispatchKind.PermanentFailure =>
                    new MobileNotificationTransportOutcome(
                        MobileNotificationTransportOutcomeKind.PermanentFailure,
                        pushResult.SafeErrorCode,
                        Transport: "Fcm"),

                ApplicationPushDispatchKind.Ambiguous =>
                    new MobileNotificationTransportOutcome(
                        MobileNotificationTransportOutcomeKind.Ambiguous,
                        pushResult.SafeErrorCode
                            ?? "push-provider-outcome-unknown",
                        Transport: "Fcm"),

                ApplicationPushDispatchKind.ProviderDisabled
                    or ApplicationPushDispatchKind.MissingConfiguration
                    or ApplicationPushDispatchKind.RuntimeUnavailable =>
                    new MobileNotificationTransportOutcome(
                        MobileNotificationTransportOutcomeKind.NoAvailableRoute,
                        pushResult.SafeErrorCode,
                        Transport: "Fcm"),

                _ =>
                    new MobileNotificationTransportOutcome(
                        MobileNotificationTransportOutcomeKind.TransientFailure,
                        pushResult.SafeErrorCode,
                        Transport: "Fcm")
            };
        }
        catch (OperationCanceledException)
            when (cancellationToken.IsCancellationRequested)
        {
            throw;
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
                sideEffectInvocationStarted
                    ? MobileNotificationTransportOutcomeKind.Ambiguous
                    : MobileNotificationTransportOutcomeKind.TransientFailure,
                sideEffectInvocationStarted
                    ? "transport-outcome-unknown"
                    : "transport-unavailable");
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

}
