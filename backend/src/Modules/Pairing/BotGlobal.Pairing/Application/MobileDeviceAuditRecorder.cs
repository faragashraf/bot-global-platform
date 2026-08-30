using BotGlobal.Pairing.Domain;
using BotGlobal.Pairing.Infrastructure.Persistence;

namespace BotGlobal.Pairing.Application;

public sealed class MobileDeviceAuditRecorder(
    PairingDbContext dbContext)
{
    public void Record(
        Guid mobileDeviceId,
        Guid platformClientId,
        string kind,
        string actorType,
        string? actorDisplayName,
        string? detail,
        DateTimeOffset occurredAtUtc)
    {
        dbContext.DeviceAuditEntries.Add(
            new MobileDeviceAuditEntry(
                Guid.NewGuid(),
                mobileDeviceId,
                platformClientId,
                kind,
                actorType,
                actorDisplayName,
                detail,
                occurredAtUtc));
    }
}
