using SentriCam.Contracts.Audit;

namespace SentriCam.Application.Abstractions.Auditing;

public interface IAuditWriter
{
    ValueTask WriteAsync(
        AuditEntry entry,
        CancellationToken cancellationToken = default);
}
