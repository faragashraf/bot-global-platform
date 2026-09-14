using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Notifications;
using BotGlobal.Pairing.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Pairing.Application;

internal sealed class PairingAccountAccessRevocationHandler(
    PairingDbContext dbContext,
    IPlatformClientApplicationResolver applications,
    TimeProvider timeProvider) : IApplicationAccountDeletionHandler
{
    public string StepName => "pairing-access-revocation";
    public int Order => 50;
    public bool RevokesAccess => true;

    public async Task DeleteAsync(ApplicationAccountDeletionScope scope, CancellationToken cancellationToken)
    {
        var application = await applications.FindByClientKeyAsync(
            scope.Identity.ApplicationKey,
            cancellationToken) ?? throw new InvalidOperationException("Application scope is unavailable.");
        var devices = await dbContext.Devices
            .Where(item => item.PlatformClientId == application.PlatformClientId
                && item.ExternalSubjectId == scope.Identity.SubjectId)
            .ToListAsync(cancellationToken);
        scope.IncludeMobileDeviceIds(devices.Select(item => item.Id));
        var ownedDeviceIds = devices.Select(item => item.Id).ToArray();
        var registrations = await dbContext.PushRegistrations
            .Where(item => ownedDeviceIds.Contains(item.MobileDeviceId))
            .ToListAsync(cancellationToken);

        var now = timeProvider.GetUtcNow();
        foreach (var device in devices) device.Revoke(now);
        foreach (var registration in registrations) registration.Invalidate(now);
        await dbContext.SaveChangesAsync(cancellationToken);
    }
}

internal sealed class PairingAccountDeletionHandler(
    PairingDbContext dbContext,
    IPlatformClientApplicationResolver applications) : IApplicationAccountDeletionHandler
{
    public string StepName => "pairing-data-deletion";
    public int Order => 400;

    public async Task DeleteAsync(ApplicationAccountDeletionScope scope, CancellationToken cancellationToken)
    {
        var application = await applications.FindByClientKeyAsync(
            scope.Identity.ApplicationKey,
            cancellationToken) ?? throw new InvalidOperationException("Application scope is unavailable.");
        var deviceIds = scope.MobileDeviceIds.Distinct().ToArray();
        var devices = await dbContext.Devices
            .Where(item => deviceIds.Contains(item.Id)
                && item.PlatformClientId == application.PlatformClientId
                && item.ExternalSubjectId == scope.Identity.SubjectId)
            .ToListAsync(cancellationToken);
        var ownedDeviceIds = devices.Select(item => item.Id).ToArray();
        var registrations = await dbContext.PushRegistrations
            .Where(item => ownedDeviceIds.Contains(item.MobileDeviceId))
            .ToListAsync(cancellationToken);

        var audits = await dbContext.DeviceAuditEntries
            .Where(item => ownedDeviceIds.Contains(item.MobileDeviceId))
            .ToListAsync(cancellationToken);
        var profiles = await dbContext.ProfileSnapshots
            .Where(item => item.PlatformClientId == application.PlatformClientId
                && item.ExternalSubjectId == scope.Identity.SubjectId)
            .ToListAsync(cancellationToken);
        var challenges = await dbContext.Challenges
            .Where(item => item.PlatformClientId == application.PlatformClientId
                && item.ExternalSubjectId == scope.Identity.SubjectId)
            .ToListAsync(cancellationToken);

        dbContext.DeviceAuditEntries.RemoveRange(audits);
        dbContext.PushRegistrations.RemoveRange(registrations);
        dbContext.Devices.RemoveRange(devices);
        dbContext.ProfileSnapshots.RemoveRange(profiles);
        dbContext.Challenges.RemoveRange(challenges);
        await dbContext.SaveChangesAsync(cancellationToken);
    }
}
