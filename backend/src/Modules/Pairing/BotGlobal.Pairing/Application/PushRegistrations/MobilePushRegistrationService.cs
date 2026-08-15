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
        }
        else
        {
            registration.Refresh(
                request.RegistrationToken,
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

        foreach (var registration in registrations)
        {
            registration.Invalidate(now);
        }

        await dbContext.SaveChangesAsync(
            cancellationToken);
    }
}
