using SentriCam.Domain.Common.Events;

namespace SentriCam.Application.Abstractions.Messaging;

public interface IOutboxWriter
{
    void Enqueue(DomainEvent domainEvent);
}
