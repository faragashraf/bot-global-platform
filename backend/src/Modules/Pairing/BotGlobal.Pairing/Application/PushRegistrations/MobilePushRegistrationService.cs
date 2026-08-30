using BotGlobal.Pairing.Domain;
using BotGlobal.Pairing.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Pairing.Application.PushRegistrations;

public sealed record RegisterMobilePushRequest(
    string Provider,
    string RegistrationToken);

public sealed record MobilePushRegistrationResult(
    Guid DeviceId,
    string Provider,
    DateTimeOffset UpdatedAtUtc);

public interface IMobilePushRegistrationService
{
    Task<MobilePushRegistrationResult> RegisterAsync(
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
    : IMobilePushRegistrationService
{
    public async Task<MobilePushRegistrationResult> RegisterAsync(
        Guid deviceId,
        RegisterMobilePushRequest request,
        CancellationToken cancellationToken)
    {
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
