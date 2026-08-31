using System.Text.Json;
using SentriCam.Application.Abstractions.Messaging;
using SentriCam.Application.Messaging;
using SentriCam.Domain.Common.Events;

namespace SentriCam.Infrastructure.Persistence.Outbox;

public sealed class OutboxWriter(
    SentriCamDbContext dbContext,
    TimeProvider timeProvider) : IOutboxWriter
{
    public void Enqueue(DomainEvent domainEvent)
    {
        ArgumentNullException.ThrowIfNull(domainEvent);
        var payloadJson = JsonSerializer.Serialize(domainEvent, domainEvent.GetType());
        dbContext.OutboxMessages.Add(OutboxMessage.Create(
            domainEvent,
            payloadJson,
            timeProvider.GetUtcNow()));
    }
}
