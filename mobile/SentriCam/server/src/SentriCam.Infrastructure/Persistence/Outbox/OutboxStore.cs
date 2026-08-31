using Microsoft.EntityFrameworkCore;
using SentriCam.Application.Abstractions.Messaging;
using SentriCam.Application.Messaging;

namespace SentriCam.Infrastructure.Persistence.Outbox;

public sealed class OutboxStore(SentriCamDbContext dbContext) : IOutboxStore
{
    public async Task<IReadOnlyList<OutboxMessage>> GetPendingAsync(
        int batchSize,
        CancellationToken cancellationToken = default) =>
        await dbContext.OutboxMessages
            .Where(message => message.ProcessedAtUtc == null)
            .OrderBy(message => message.CreatedAtUtc)
            .Take(batchSize)
            .ToListAsync(cancellationToken);
}
