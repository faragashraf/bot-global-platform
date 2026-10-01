using SentriCam.Application.Messaging;

namespace SentriCam.Application.Abstractions.Messaging;

public interface IOutboxStore
{
    Task<IReadOnlyList<OutboxMessage>> GetPendingAsync(
        int batchSize,
        CancellationToken cancellationToken = default);
}
