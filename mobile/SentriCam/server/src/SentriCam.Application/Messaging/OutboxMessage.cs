using SentriCam.Contracts.Events;
using SentriCam.Domain.Common.Events;

namespace SentriCam.Application.Messaging;

public sealed class OutboxMessage
{
    private OutboxMessage()
    {
    }

    private OutboxMessage(
        Guid id,
        Guid eventId,
        string eventType,
        int schemaVersion,
        string payloadJson,
        DateTimeOffset occurredAtUtc,
        DateTimeOffset createdAtUtc)
    {
        Id = id;
        EventId = eventId;
        EventType = eventType;
        SchemaVersion = schemaVersion;
        PayloadJson = payloadJson;
        OccurredAtUtc = occurredAtUtc;
        CreatedAtUtc = createdAtUtc;
    }

    public Guid Id { get; private set; }

    public Guid EventId { get; private set; }

    public string EventType { get; private set; } = string.Empty;

    public int SchemaVersion { get; private set; }

    public string PayloadJson { get; private set; } = string.Empty;

    public DateTimeOffset OccurredAtUtc { get; private set; }

    public DateTimeOffset CreatedAtUtc { get; private set; }

    public DateTimeOffset? ProcessedAtUtc { get; private set; }

    public int AttemptCount { get; private set; }

    public static OutboxMessage Create(
        DomainEvent domainEvent,
        string payloadJson,
        DateTimeOffset createdAtUtc)
    {
        ArgumentNullException.ThrowIfNull(domainEvent);
        if (string.IsNullOrWhiteSpace(payloadJson))
        {
            throw new ArgumentException("Outbox payload is required.", nameof(payloadJson));
        }

        return new OutboxMessage(
            Guid.NewGuid(),
            domainEvent.EventId,
            domainEvent.EventType,
            domainEvent.SchemaVersion,
            payloadJson,
            domainEvent.OccurredAtUtc,
            createdAtUtc);
    }

    public IntegrationEvent ToIntegrationEvent() =>
        new(EventId, EventType, SchemaVersion, OccurredAtUtc, PayloadJson);

    public void MarkProcessed(DateTimeOffset processedAtUtc)
    {
        if (ProcessedAtUtc is not null)
        {
            return;
        }

        AttemptCount++;
        ProcessedAtUtc = processedAtUtc;
    }
}
