using SentriCam.Application.Abstractions.Messaging;
using SentriCam.Application.Abstractions.Persistence;

namespace SentriCam.Application.Messaging;

public sealed class OutboxDispatcher(
    IOutboxStore outboxStore,
    IIntegrationEventPublisher publisher,
    IUnitOfWork unitOfWork,
    TimeProvider timeProvider)
{
    public async Task<int> DispatchPendingAsync(
        int batchSize,
        CancellationToken cancellationToken = default)
    {
        if (batchSize is <= 0 or > 1_000)
        {
            throw new ArgumentOutOfRangeException(
                nameof(batchSize),
                batchSize,
                "Outbox batch size must be between 1 and 1000.");
        }

        var messages = await outboxStore.GetPendingAsync(batchSize, cancellationToken);
        foreach (var message in messages)
        {
            await publisher.PublishAsync(message.ToIntegrationEvent(), cancellationToken);
            message.MarkProcessed(timeProvider.GetUtcNow());
        }

        if (messages.Count > 0)
        {
            await unitOfWork.SaveChangesAsync(cancellationToken);
        }

        return messages.Count;
    }
}
