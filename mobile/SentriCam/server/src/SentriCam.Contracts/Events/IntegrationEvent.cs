namespace SentriCam.Contracts.Events;

public record IntegrationEvent(
    Guid EventId,
    string EventType,
    int SchemaVersion,
    DateTimeOffset OccurredAtUtc,
    string PayloadJson);
