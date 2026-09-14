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

            // A provider rejection can invalidate the old destination after
            // this request reads it. A successful refresh must explicitly clear
            // that invalidation even when the originally tracked value was null.
            dbContext.Entry(registration)
                .Property(item => item.InvalidatedAtUtc).IsModified = true;

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

        var deviceRemainsActive = await dbContext.Devices
            .AsNoTracking()
            .AnyAsync(
                device => device.Id == deviceId
                    && device.PlatformClientId == application.ApplicationId
                    && device.RevokedAtUtc == null,
                cancellationToken);
        if (!deviceRemainsActive)
        {
            var registrations = dbContext.PushRegistrations.Where(item =>
                item.MobileDeviceId == deviceId
                && item.Provider == provider
                && item.InvalidatedAtUtc == null
                && !dbContext.Devices.Any(device =>
                    device.Id == item.MobileDeviceId
                    && device.PlatformClientId == application.ApplicationId
                    && device.RevokedAtUtc == null));

            if (dbContext.Database.IsRelational())
            {
                await registrations.ExecuteUpdateAsync(
                    setters => setters
                        .SetProperty(item => item.InvalidatedAtUtc, now)
                        .SetProperty(item => item.UpdatedAtUtc, now),
                    cancellationToken);
            }
            else
            {
                foreach (var staleRegistration in await registrations.ToListAsync(cancellationToken))
                {
                    staleRegistration.Invalidate(now);
                }

                await dbContext.SaveChangesAsync(cancellationToken);
            }

            throw new InvalidOperationException(
                "Authenticated mobile device became unavailable or revoked.");
        }

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
        string rejectedRegistrationToken,
        string safeReason,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(application);
        ArgumentException.ThrowIfNullOrWhiteSpace(rejectedRegistrationToken);
        var normalizedProvider = provider?.Trim().ToLowerInvariant();
        if (deviceId == Guid.Empty || normalizedProvider != "fcm")
        {
            return;
        }

        var registrations = dbContext.PushRegistrations.Where(registration =>
            registration.MobileDeviceId == deviceId
            && registration.Provider == normalizedProvider
            && registration.InvalidatedAtUtc == null
            && dbContext.Devices.Any(device =>
                device.Id == registration.MobileDeviceId
                && device.PlatformClientId == application.ApplicationId));
        var now = timeProvider.GetUtcNow();
        await using var transaction = dbContext.Database.IsRelational()
            ? await dbContext.Database.BeginTransactionAsync(cancellationToken)
            : null;

        if (dbContext.Database.IsRelational())
        {
            // Compare and invalidate in ONE statement. A late result for an
            // old token must not revoke a replacement registered during send.
            // Tokens are case-sensitive even in a case-insensitive database.
            var changed = await registrations
                .Where(registration => EF.Functions.Collate(
                    registration.RegistrationToken, "Latin1_General_100_BIN2")
                    == rejectedRegistrationToken)
                .ExecuteUpdateAsync(setters => setters
                    .SetProperty(registration => registration.InvalidatedAtUtc, now)
                    .SetProperty(registration => registration.UpdatedAtUtc, now),
                    cancellationToken);
            if (changed == 0)
            {
                return;
            }
        }
        else
        {
            var registration = await registrations.SingleOrDefaultAsync(cancellationToken);
            if (registration is null || !string.Equals(
                    registration.RegistrationToken, rejectedRegistrationToken, StringComparison.Ordinal))
            {
                return;
            }

            registration.Invalidate(now);
        }

        auditRecorder.Record(
            deviceId,
            application.ApplicationId,
            MobileDeviceAuditKinds.PushInvalidated,
            MobileDeviceAuditActorTypes.System,
            null,
            $"provider={normalizedProvider}; reason={NormalizeSafeReason(safeReason)}",
            now);
        await dbContext.SaveChangesAsync(cancellationToken);
        if (transaction is not null)
        {
            await transaction.CommitAsync(cancellationToken);
        }
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
