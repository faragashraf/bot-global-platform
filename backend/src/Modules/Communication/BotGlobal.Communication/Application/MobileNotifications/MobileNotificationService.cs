using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Notifications;
using BotGlobal.Communication.Contracts.MobileNotifications;

namespace BotGlobal.Communication.Application.MobileNotifications;

public interface IMobileNotificationService
{
    Task<SendMobileNotificationResponse> SendAsync(
        Guid platformClientId,
        SendMobileNotificationRequest request,
        CancellationToken cancellationToken);
}

internal sealed class MobileNotificationService(
    IMobileRecipientResolver recipientResolver,
    IMobileNotificationDelivery delivery,
    TimeProvider timeProvider)
    : IMobileNotificationService
{
    public async Task<SendMobileNotificationResponse> SendAsync(
        Guid platformClientId,
        SendMobileNotificationRequest request,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(request);

        Validate(request);

        var subjectId =
            request.RecipientExternalSubjectId.Trim();
        var application =
            new NotificationApplicationContext(platformClientId);

        var devices =
            await recipientResolver.ResolveActiveDevicesAsync(
                application,
                subjectId,
                cancellationToken);

        var notification =
            new MobileNotificationEnvelope(
                Guid.NewGuid().ToString("N"),
                subjectId,
                request.TitleAr.Trim(),
                request.TitleEn.Trim(),
                request.BodyAr.Trim(),
                request.BodyEn.Trim(),
                request.Type.Trim(),
                request.Priority,
                timeProvider.GetUtcNow(),
                request.Data);

        var result =
            await delivery.DeliverAsync(
                application,
                notification,
                devices,
                cancellationToken);

        return new SendMobileNotificationResponse(
            notification.NotificationId,
            subjectId,
            devices.Count,
            ResolveDeliveryStatus(
                result,
                devices.Count));
    }

    private static string ResolveDeliveryStatus(
        MobileNotificationDeliveryResult result,
        int activeDeviceCount)
    {
        if (
            result.SignalRDeliveredDeviceCount > 0
            && result.FcmDeliveredDeviceCount > 0)
        {
            return "mixed-dispatched";
        }

        if (result.SignalRDeliveredDeviceCount > 0)
        {
            return "signalr-dispatched";
        }

        if (result.FcmDeliveredDeviceCount > 0)
        {
            return "fcm-dispatched";
        }

        return activeDeviceCount > 0
            ? "accepted"
            : "no-active-devices";
    }

    private static void Validate(
        SendMobileNotificationRequest request)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(
            request.RecipientExternalSubjectId);

        ArgumentException.ThrowIfNullOrWhiteSpace(request.TitleAr);
        ArgumentException.ThrowIfNullOrWhiteSpace(request.TitleEn);
        ArgumentException.ThrowIfNullOrWhiteSpace(request.BodyAr);
        ArgumentException.ThrowIfNullOrWhiteSpace(request.BodyEn);
        ArgumentException.ThrowIfNullOrWhiteSpace(request.Type);

        if (request.TitleAr.Trim().Length > 200 ||
            request.TitleEn.Trim().Length > 200)
        {
            throw new ArgumentException(
                "Notification title cannot exceed 200 characters.");
        }

        if (request.BodyAr.Trim().Length > 4000 ||
            request.BodyEn.Trim().Length > 4000)
        {
            throw new ArgumentException(
                "Notification body cannot exceed 4000 characters.");
        }

        if (!Enum.IsDefined(request.Priority))
        {
            throw new ArgumentException(
                "Notification priority is invalid.");
        }
    }
}
