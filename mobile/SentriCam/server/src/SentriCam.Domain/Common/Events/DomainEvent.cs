namespace SentriCam.Domain.Common.Events;

public abstract record DomainEvent
{
    protected DomainEvent(DateTimeOffset occurredAtUtc)
    {
        EventId = Guid.NewGuid();
        OccurredAtUtc = occurredAtUtc;
    }

    public Guid EventId { get; }

    public DateTimeOffset OccurredAtUtc { get; }

    public abstract string EventType { get; }

    public virtual int SchemaVersion => 1;
}
