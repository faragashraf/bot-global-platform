using SentriCam.Application.Messaging;
using SentriCam.Domain.Devices.Events;
using SentriCam.Tests.TestDoubles;

namespace SentriCam.Tests.Application;

public sealed class OutboxFoundationTests
{
    [Fact]
    public void OutboxMessagePreservesDomainEventIdentityAndSchema()
    {
        var device = DeviceTestFactory.CreateDevice();
        var domainEvent = Assert.IsType<DeviceRegisteredDomainEvent>(Assert.Single(device.DomainEvents));

        var message = OutboxMessage.Create(domainEvent, "{\"device\":\"registered\"}", DeviceTestFactory.Now);
        var integrationEvent = message.ToIntegrationEvent();

        Assert.Equal(domainEvent.EventId, message.EventId);
        Assert.Equal(domainEvent.EventType, integrationEvent.EventType);
        Assert.Equal(domainEvent.SchemaVersion, integrationEvent.SchemaVersion);
        Assert.Equal(domainEvent.OccurredAtUtc, integrationEvent.OccurredAtUtc);
        Assert.Null(message.ProcessedAtUtc);
    }

    [Fact]
    public async Task DispatcherUsesSharedIntegrationEventContractWhenExplicitlyInvoked()
    {
        var device = DeviceTestFactory.CreateDevice();
        var domainEvent = Assert.IsType<DeviceRegisteredDomainEvent>(Assert.Single(device.DomainEvents));
        var message = OutboxMessage.Create(domainEvent, "{}", DeviceTestFactory.Now);
        var publisher = new FakeIntegrationEventPublisher();
        var unitOfWork = new FakeUnitOfWork();
        var dispatchedAt = DeviceTestFactory.Now.AddMinutes(1);
        var dispatcher = new OutboxDispatcher(
            new FakeOutboxStore(message),
            publisher,
            unitOfWork,
            new FixedTimeProvider(dispatchedAt));

        var count = await dispatcher.DispatchPendingAsync(
            10,
            TestContext.Current.CancellationToken);

        Assert.Equal(1, count);
        Assert.Equal(message.EventId, Assert.Single(publisher.PublishedEvents).EventId);
        Assert.Equal(dispatchedAt, message.ProcessedAtUtc);
        Assert.Equal(1, message.AttemptCount);
        Assert.Equal(1, unitOfWork.SaveCount);
    }
}
