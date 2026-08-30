using BotGlobal.Contracts.Notifications;
using BotGlobal.Pairing.Domain;
using BotGlobal.Pairing.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Pairing.Application.AdminDevicePairings;

public sealed record AdminDevicePairingListItem(
    Guid DeviceId,
    Guid PlatformClientId,
    string PlatformClientDisplayName,
    string? ExternalSubjectId,
    string InstallationId,
    string Platform,
    string? DeviceName,
    string? AppVersion,
    DateTimeOffset CreatedAtUtc,
    DateTimeOffset LastPairedAtUtc,
    DateTimeOffset? RevokedAtUtc,
    bool IsActive,
    bool HasActivePushRegistration);

public sealed record AdminDevicePairingTimelineEntry(
    DateTimeOffset OccurredAtUtc,
    string Kind,
    string ActorType,
    string? ActorDisplayName,
    string? Detail,
    string Source);

public sealed record AdminDevicePairingDetail(
    AdminDevicePairingListItem Device,
    IReadOnlyList<AdminDevicePushRegistrationItem> PushRegistrations,
    IReadOnlyList<AdminDevicePairingTimelineEntry> Timeline,
    int DeliveryLogCount);

public sealed record AdminDevicePushRegistrationItem(
    Guid Id,
    string Provider,
    DateTimeOffset CreatedAtUtc,
    DateTimeOffset UpdatedAtUtc,
    DateTimeOffset? InvalidatedAtUtc);

public sealed record AdminRevokeDeviceCommand(
    Guid DeviceId,
    bool PurgeHistory,
    Guid AdministratorUserId,
    string AdministratorDisplayName);

public sealed record AdminRevokeDeviceResult(
    Guid DeviceId,
    bool AlreadyRevoked,
    bool PurgedHistory,
    int PurgedAuditEntries,
    int PurgedDeliveryEntries);

public interface IAdminDevicePairingService
{
    Task<IReadOnlyList<AdminDevicePairingListItem>> ListAsync(
        CancellationToken cancellationToken);

    Task<AdminDevicePairingDetail?> FindAsync(
        Guid deviceId,
        CancellationToken cancellationToken);

    Task<AdminRevokeDeviceResult> RevokeAsync(
        AdminRevokeDeviceCommand command,
        CancellationToken cancellationToken);
}

internal sealed class AdminDevicePairingService(
    PairingDbContext dbContext,
    IPlatformClientDescriptorReader platformClients,
    INotificationDeviceLogReader notificationLogs,
    MobileDeviceAuditRecorder auditRecorder,
    TimeProvider timeProvider)
    : IAdminDevicePairingService
{
    public async Task<IReadOnlyList<AdminDevicePairingListItem>>
        ListAsync(CancellationToken cancellationToken)
    {
        var devices = await dbContext.Devices
            .AsNoTracking()
            .OrderByDescending(device => device.CreatedAtUtc)
            .ToListAsync(cancellationToken);

        var activeRegistrations = await dbContext.PushRegistrations
            .AsNoTracking()
            .Where(registration => registration.InvalidatedAtUtc == null)
            .Select(registration => registration.MobileDeviceId)
            .Distinct()
            .ToListAsync(cancellationToken);

        var activeRegistrationSet =
            activeRegistrations.ToHashSet();

        var clientNames = new Dictionary<Guid, string>();

        foreach (var device in devices)
        {
            if (clientNames.ContainsKey(device.PlatformClientId))
            {
                continue;
            }

            var descriptor = await platformClients.FindAsync(
                device.PlatformClientId,
                cancellationToken);

            clientNames[device.PlatformClientId] =
                descriptor?.DisplayName ?? "Unknown application";
        }

        return devices
            .Select(device => new AdminDevicePairingListItem(
                device.Id,
                device.PlatformClientId,
                clientNames[device.PlatformClientId],
                device.ExternalSubjectId,
                device.InstallationId,
                device.Platform,
                device.DeviceName,
                device.AppVersion,
                device.CreatedAtUtc,
                device.LastPairedAtUtc,
                device.RevokedAtUtc,
                device.IsActive,
                activeRegistrationSet.Contains(device.Id)))
            .ToList();
    }

    public async Task<AdminDevicePairingDetail?> FindAsync(
        Guid deviceId,
        CancellationToken cancellationToken)
    {
        var device = await dbContext.Devices
            .AsNoTracking()
            .SingleOrDefaultAsync(
                candidate => candidate.Id == deviceId,
                cancellationToken);

        if (device is null)
        {
            return null;
        }

        var registrations = await dbContext.PushRegistrations
            .AsNoTracking()
            .Where(registration => registration.MobileDeviceId == deviceId)
            .OrderByDescending(registration => registration.UpdatedAtUtc)
            .Select(registration => new AdminDevicePushRegistrationItem(
                registration.Id,
                registration.Provider,
                registration.CreatedAtUtc,
                registration.UpdatedAtUtc,
                registration.InvalidatedAtUtc))
            .ToListAsync(cancellationToken);

        var auditEntries = await dbContext.DeviceAuditEntries
            .AsNoTracking()
            .Where(entry => entry.MobileDeviceId == deviceId)
            .ToListAsync(cancellationToken);

        var deliveryLogs = await notificationLogs.ReadForDeviceAsync(
            deviceId,
            cancellationToken);

        var descriptor = await platformClients.FindAsync(
            device.PlatformClientId,
            cancellationToken);

        var listItem = new AdminDevicePairingListItem(
            device.Id,
            device.PlatformClientId,
            descriptor?.DisplayName ?? "Unknown application",
            device.ExternalSubjectId,
            device.InstallationId,
            device.Platform,
            device.DeviceName,
            device.AppVersion,
            device.CreatedAtUtc,
            device.LastPairedAtUtc,
            device.RevokedAtUtc,
            device.IsActive,
            registrations.Any(registration =>
                registration.InvalidatedAtUtc is null));

        var timeline = BuildTimeline(
            device,
            auditEntries,
            registrations,
            deliveryLogs);

        return new AdminDevicePairingDetail(
            listItem,
            registrations,
            timeline,
            deliveryLogs.Count);
    }

    public async Task<AdminRevokeDeviceResult> RevokeAsync(
        AdminRevokeDeviceCommand command,
        CancellationToken cancellationToken)
    {
        var device = await dbContext.Devices
            .SingleOrDefaultAsync(
                candidate => candidate.Id == command.DeviceId,
                cancellationToken);

        if (device is null)
        {
            throw new InvalidOperationException(
                "Mobile device was not found.");
        }

        var now = timeProvider.GetUtcNow();
        var alreadyRevoked = !device.IsActive;

        if (!alreadyRevoked)
        {
            device.Revoke(now);

            auditRecorder.Record(
                device.Id,
                device.PlatformClientId,
                MobileDeviceAuditKinds.RevokedByAdministrator,
                MobileDeviceAuditActorTypes.Administrator,
                command.AdministratorDisplayName,
                "Administrator revoked this device pairing.",
                now);

            var pushRegistrations = await dbContext.PushRegistrations
                .Where(registration =>
                    registration.MobileDeviceId == device.Id
                    && registration.InvalidatedAtUtc == null)
                .ToListAsync(cancellationToken);

            foreach (var registration in pushRegistrations)
            {
                registration.Invalidate(now);

                auditRecorder.Record(
                    device.Id,
                    device.PlatformClientId,
                    MobileDeviceAuditKinds.PushInvalidated,
                    MobileDeviceAuditActorTypes.System,
                    null,
                    $"provider={registration.Provider}",
                    now);
            }

            await dbContext.SaveChangesAsync(cancellationToken);
        }

        var purgedAuditEntries = 0;
        var purgedDeliveryEntries = 0;

        if (command.PurgeHistory && !alreadyRevoked)
        {
            purgedAuditEntries = await dbContext.DeviceAuditEntries
                .Where(entry => entry.MobileDeviceId == device.Id)
                .ExecuteDeleteAsync(cancellationToken);

            purgedDeliveryEntries =
                await notificationLogs.PurgeForDeviceAsync(
                    device.Id,
                    cancellationToken);

            auditRecorder.Record(
                device.Id,
                device.PlatformClientId,
                MobileDeviceAuditKinds.HistoryPurged,
                MobileDeviceAuditActorTypes.Administrator,
                command.AdministratorDisplayName,
                $"purged audit={purgedAuditEntries}; delivery={purgedDeliveryEntries}",
                timeProvider.GetUtcNow());

            await dbContext.SaveChangesAsync(cancellationToken);
        }

        return new AdminRevokeDeviceResult(
            device.Id,
            alreadyRevoked,
            command.PurgeHistory && !alreadyRevoked,
            purgedAuditEntries,
            purgedDeliveryEntries);
    }

    private static List<AdminDevicePairingTimelineEntry> BuildTimeline(
        MobileDevice device,
        IReadOnlyList<MobileDeviceAuditEntry> auditEntries,
        IReadOnlyList<AdminDevicePushRegistrationItem> registrations,
        IReadOnlyList<MobileDeviceDeliveryLogEntry> deliveryLogs)
    {
        var timeline = auditEntries
            .Select(entry => new AdminDevicePairingTimelineEntry(
                entry.OccurredAtUtc,
                entry.Kind,
                entry.ActorType,
                entry.ActorDisplayName,
                entry.Detail,
                "audit"))
            .ToList();

        if (auditEntries.All(entry =>
                entry.OccurredAtUtc != device.CreatedAtUtc))
        {
            timeline.Add(new AdminDevicePairingTimelineEntry(
                device.CreatedAtUtc,
                MobileDeviceAuditKinds.Paired,
                MobileDeviceAuditActorTypes.Device,
                null,
                $"platform={device.Platform}; installation={device.InstallationId} (derived)",
                "derived"));
        }

        if (device.RevokedAtUtc is not null
            && auditEntries.All(entry =>
                entry.Kind != MobileDeviceAuditKinds.RevokedByAdministrator
                && entry.Kind != MobileDeviceAuditKinds.UnpairedByDevice))
        {
            timeline.Add(new AdminDevicePairingTimelineEntry(
                device.RevokedAtUtc.Value,
                MobileDeviceAuditKinds.UnpairedByDevice,
                MobileDeviceAuditActorTypes.System,
                null,
                "Revocation recorded before auditing existed (derived)",
                "derived"));
        }

        foreach (var registration in registrations)
        {
            timeline.Add(new AdminDevicePairingTimelineEntry(
                registration.CreatedAtUtc,
                MobileDeviceAuditKinds.PushRegistered,
                MobileDeviceAuditActorTypes.Device,
                null,
                $"provider={registration.Provider} (registration)",
                "derived"));

            if (registration.InvalidatedAtUtc is not null)
            {
                timeline.Add(new AdminDevicePairingTimelineEntry(
                    registration.InvalidatedAtUtc.Value,
                    MobileDeviceAuditKinds.PushInvalidated,
                    MobileDeviceAuditActorTypes.System,
                    null,
                    $"provider={registration.Provider} (registration)",
                    "derived"));
            }
        }

        foreach (var log in deliveryLogs)
        {
            if (log.OccurredAtUtc is null)
            {
                continue;
            }

            timeline.Add(new AdminDevicePairingTimelineEntry(
                log.OccurredAtUtc.Value,
                "notification-delivery",
                MobileDeviceAuditActorTypes.System,
                null,
                $"{log.Status}; campaign={log.CampaignTitleEn}"
                    + (log.SafeErrorCode is null
                        ? string.Empty
                        : $"; error={log.SafeErrorCode}"),
                "delivery"));
        }

        return timeline
            .OrderByDescending(entry => entry.OccurredAtUtc)
            .ToList();
    }
}
