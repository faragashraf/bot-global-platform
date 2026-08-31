using BotGlobal.Contracts.Notifications;
using BotGlobal.Pairing.Domain;
using BotGlobal.Pairing.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Pairing.Application.PushRegistrations;

public sealed record RegisterMobilePushRequest(
    string Provider,
    string RegistrationToken);

public sealed record MobilePushRegistrationResult(
    Guid DeviceId,
    Guid ApplicationId,
    string Provider,
    DateTimeOffset UpdatedAtUtc);

public interface IMobilePushRegistrationService
{
    Task<MobilePushRegistrationResult> RegisterAsync(
        NotificationApplicationContext application,
        Guid deviceId,
        RegisterMobilePushRequest request,
        CancellationToken cancellationToken);

    Task InvalidateAllAsync(
        Guid deviceId,
        CancellationToken cancellationToken);
}

internal sealed class MobilePushRegistrationService(
    PairingDbContext dbContext,
    MobileDeviceAuditRecorder auditRecorder,
    TimeProvider timeProvider)
    : IMobilePushRegistrationService,
      IMobilePushDestinationInvalidator
{
    public async Task<MobilePushRegistrationResult> RegisterAsync(
        NotificationApplicationContext application,
        Guid deviceId,
        RegisterMobilePushRequest request,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(application);
        ArgumentNullException.ThrowIfNull(request);

        if (deviceId == Guid.Empty)
        {
            throw new ArgumentException(
                "Authenticated mobile device id is required.",
                nameof(deviceId));
        }

        var provider =
            request.Provider?.Trim().ToLowerInvariant();

        if (provider != "fcm")
        {
            throw new ArgumentException(
                "Unsupported mobile push provider.",
                nameof(request));
        }

        if (string.IsNullOrWhiteSpace(
                request.RegistrationToken))
        {
            throw new ArgumentException(
                "Registration token is required.",
                nameof(request));
        }

        var activeDeviceExists =
            await dbContext.Devices
                .AsNoTracking()
                .AnyAsync(
                    device =>
                        device.Id == deviceId
                        && device.PlatformClientId == application.ApplicationId
                        && device.RevokedAtUtc == null,
                    cancellationToken);

        if (!activeDeviceExists)
        {
            throw new InvalidOperationException(
                "Authenticated mobile device is unavailable or revoked.");
        }

        var now =
            timeProvider.GetUtcNow();

        var registration =
            await dbContext.PushRegistrations
                .SingleOrDefaultAsync(
                    item =>
                        item.MobileDeviceId == deviceId
                        && item.Provider == provider,
                    cancellationToken);

        if (registration is null)
        {
            registration =
                new MobilePushRegistration(
                    deviceId,
                    provider,
                    request.RegistrationToken,
                    now);

            dbContext.PushRegistrations.Add(
                registration);

            auditRecorder.Record(
                deviceId,
                await ResolvePlatformClientIdAsync(
                    deviceId,
                    cancellationToken),
                MobileDeviceAuditKinds.PushRegistered,
                MobileDeviceAuditActorTypes.Device,
                null,
                $"provider={provider}",
                now);
        }
        else
        {
            var wasInvalidated =
                registration.InvalidatedAtUtc is not null;

            registration.Refresh(
                request.RegistrationToken,
                now);

            auditRecorder.Record(
                deviceId,
                await ResolvePlatformClientIdAsync(
                    deviceId,
                    cancellationToken),
                wasInvalidated
                    ? MobileDeviceAuditKinds.PushRegistered
                    : MobileDeviceAuditKinds.PushRefreshed,
                MobileDeviceAuditActorTypes.Device,
                null,
                $"provider={provider}",
                now);
        }

        await dbContext.SaveChangesAsync(
            cancellationToken);

        return new MobilePushRegistrationResult(
            deviceId,
            application.ApplicationId,
            provider,
            now);
    }

    public async Task InvalidateAllAsync(
        Guid deviceId,
        CancellationToken cancellationToken)
    {
        var registrations =
            await dbContext.PushRegistrations
                .Where(
                    item =>
                        item.MobileDeviceId == deviceId
                        && item.InvalidatedAtUtc == null)
                .ToListAsync(cancellationToken);

        if (registrations.Count == 0)
        {
            return;
        }

        var now =
            timeProvider.GetUtcNow();

        var platformClientId =
            await ResolvePlatformClientIdAsync(
                deviceId,
                cancellationToken);

        foreach (var registration in registrations)
        {
            registration.Invalidate(now);

            auditRecorder.Record(
                deviceId,
                platformClientId,
                MobileDeviceAuditKinds.PushInvalidated,
                MobileDeviceAuditActorTypes.System,
                null,
                $"provider={registration.Provider}",
                now);
        }

        await dbContext.SaveChangesAsync(
            cancellationToken);
    }

    public async Task InvalidateAsync(
        NotificationApplicationContext application,
        Guid deviceId,
        string provider,
        string safeReason,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(application);
        var normalizedProvider = provider?.Trim().ToLowerInvariant();
        if (deviceId == Guid.Empty || normalizedProvider != "fcm")
        {
            return;
        }

        var registration = await (
                from pushRegistration in dbContext.PushRegistrations
                join device in dbContext.Devices
                    on pushRegistration.MobileDeviceId equals device.Id
                where device.Id == deviceId
                      && device.PlatformClientId == application.ApplicationId
                      && pushRegistration.Provider == normalizedProvider
                      && pushRegistration.InvalidatedAtUtc == null
                select pushRegistration)
            .SingleOrDefaultAsync(cancellationToken);

        if (registration is null)
        {
            return;
        }

        var now = timeProvider.GetUtcNow();
        registration.Invalidate(now);
        auditRecorder.Record(
            deviceId,
            application.ApplicationId,
            MobileDeviceAuditKinds.PushInvalidated,
            MobileDeviceAuditActorTypes.System,
            null,
            $"provider={normalizedProvider}; reason={NormalizeSafeReason(safeReason)}",
            now);
        await dbContext.SaveChangesAsync(cancellationToken);
    }

    private static string NormalizeSafeReason(string safeReason)
    {
        var value = safeReason?.Trim().ToLowerInvariant();
        if (string.IsNullOrEmpty(value))
        {
            return "provider-rejected";
        }

        var normalized = new string(value
            .Where(character => char.IsAsciiLetterOrDigit(character) || character is '-' or '_')
            .Take(80)
            .ToArray());

        return string.IsNullOrEmpty(normalized)
            ? "provider-rejected"
            : normalized;
    }

    private async Task<Guid> ResolvePlatformClientIdAsync(
        Guid deviceId,
        CancellationToken cancellationToken)
    {
        var platformClientId = await dbContext.Devices
            .AsNoTracking()
            .Where(device => device.Id == deviceId)
            .Select(device => (Guid?)device.PlatformClientId)
            .SingleOrDefaultAsync(cancellationToken);

        return platformClientId
            ?? throw new InvalidOperationException(
                "Authenticated mobile device is unavailable or revoked.");
    }
}
