using BotGlobal.Contracts.Notifications;
using BotGlobal.Pairing.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Pairing.Application.Notifications;

internal sealed class PairingMobileBroadcastAudienceReader(
    PairingDbContext dbContext)
    : IMobileBroadcastAudienceReader
{
    public async Task<MobileBroadcastAudiencePreview> PreviewAsync(
        Guid platformClientId,
        DateTimeOffset audienceAsOfUtc,
        CancellationToken cancellationToken)
    {
        var audience = SnapshotQuery(
            platformClientId,
            audienceAsOfUtc);

        var subjectCount = await audience
            .Where(device => device.ExternalSubjectId != null)
            .Select(device => device.ExternalSubjectId)
            .Distinct()
            .CountAsync(cancellationToken);

        var deviceCount = await audience
            .CountAsync(cancellationToken);

        var pushCapableCount = await audience
            .Where(device => dbContext.PushRegistrations.Any(registration =>
                registration.MobileDeviceId == device.Id
                && registration.Provider == "fcm"
                && registration.CreatedAtUtc <= audienceAsOfUtc
                && (registration.InvalidatedAtUtc == null
                    || registration.InvalidatedAtUtc > audienceAsOfUtc)))
            .CountAsync(cancellationToken);

        return new MobileBroadcastAudiencePreview(
            subjectCount,
            deviceCount,
            pushCapableCount);
    }

    public async Task<MobileBroadcastAudiencePage> ReadPageAsync(
        Guid platformClientId,
        DateTimeOffset audienceAsOfUtc,
        Guid? afterDeviceId,
        int pageSize,
        CancellationToken cancellationToken)
    {
        if (pageSize is < 1 or > 1000)
        {
            throw new ArgumentOutOfRangeException(nameof(pageSize));
        }

        var query = SnapshotQuery(
            platformClientId,
            audienceAsOfUtc);

        if (afterDeviceId.HasValue)
        {
            var cursor = afterDeviceId.Value;
            query = query.Where(device =>
                device.Id.CompareTo(cursor) > 0);
        }

        var page = await query
            .OrderBy(device => device.Id)
            .Select(device => new MobileBroadcastAudienceDevice(
                device.Id,
                device.InstallationId,
                device.Platform,
                device.DeviceName))
            .Take(pageSize + 1)
            .ToArrayAsync(cancellationToken);

        return new MobileBroadcastAudiencePage(
            page.Take(pageSize).ToArray(),
            page.Length > pageSize);
    }

    public async Task<MobileBroadcastDeviceState>
        GetCurrentDeviceStateAsync(
            Guid platformClientId,
            Guid deviceId,
            CancellationToken cancellationToken)
    {
        var device = await dbContext.Devices
            .AsNoTracking()
            .Where(candidate =>
                candidate.Id == deviceId
                && candidate.PlatformClientId == platformClientId)
            .Select(candidate => new
            {
                candidate.RevokedAtUtc
            })
            .SingleOrDefaultAsync(cancellationToken);

        return device is null
            ? new MobileBroadcastDeviceState(false, true)
            : new MobileBroadcastDeviceState(
                true,
                device.RevokedAtUtc.HasValue);
    }

    private IQueryable<Domain.MobileDevice> SnapshotQuery(
        Guid platformClientId,
        DateTimeOffset audienceAsOfUtc)
    {
        return dbContext.Devices
            .AsNoTracking()
            .Where(device =>
                device.PlatformClientId == platformClientId
                && device.CreatedAtUtc <= audienceAsOfUtc
                && device.LastPairedAtUtc <= audienceAsOfUtc
                && (device.RevokedAtUtc == null
                    || device.RevokedAtUtc > audienceAsOfUtc));
    }
}
