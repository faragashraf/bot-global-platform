using SentriCam.Contracts.Events;

namespace SentriCam.Application.Abstractions.Messaging;

public interface IIntegrationEventPublisher
{
    Task PublishAsync(
        IntegrationEvent integrationEvent,
        CancellationToken cancellationToken = default);
}
