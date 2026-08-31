using SentriCam.Domain.Devices;
using SentriCam.Domain.Devices.Events;
using SentriCam.Tests.TestDoubles;

namespace SentriCam.Tests.Domain;

public sealed class DomainEventTests
{
    [Fact]
    public void DeviceRegistrationCollectsEventWithoutPublishingIt()
    {
        var device = DeviceTestFactory.CreateDevice();

        var registered = Assert.IsType<DeviceRegisteredDomainEvent>(Assert.Single(device.DomainEvents));

        Assert.Equal(device.Id, registered.DeviceId);
        Assert.Equal(device.Identity.InstallationId, registered.InstallationId);
        Assert.Equal("device.registered", registered.EventType);
    }

    [Fact]
    public void ConnectionLifecycleCollectsConnectedAndDisconnectedEventsOnce()
    {
        var device = DeviceTestFactory.CreateDevice();
        var connection = DeviceConnection.Connect(
            device.Id,
            "connection-1",
            DeviceTestFactory.Now);
        Assert.IsType<DeviceConnectedDomainEvent>(Assert.Single(connection.DequeueDomainEvents()));

        connection.Disconnect(DeviceTestFactory.Now.AddMinutes(1));
        connection.Disconnect(DeviceTestFactory.Now.AddMinutes(2));

        Assert.IsType<DeviceDisconnectedDomainEvent>(Assert.Single(connection.DequeueDomainEvents()));
    }

    [Fact]
    public void OperationalTransitionsAndMotionCollectAllPreparedEventTypes()
    {
        var device = DeviceTestFactory.CreateDevice();
        device.DequeueDomainEvents();

        device.ChangeStatus(DeviceStatus.Monitoring, DeviceTestFactory.Now.AddMinutes(1));
        device.ChangeStatus(DeviceStatus.Online, DeviceTestFactory.Now.AddMinutes(2));
        device.ChangeStatus(DeviceStatus.Recording, DeviceTestFactory.Now.AddMinutes(3));
        device.ChangeStatus(DeviceStatus.Online, DeviceTestFactory.Now.AddMinutes(4));
        device.RecordMotionDetected(DeviceTestFactory.Now.AddMinutes(5));

        var events = device.DequeueDomainEvents();
        Assert.Collection(
            events,
            item => Assert.IsType<MonitoringStartedDomainEvent>(item),
            item => Assert.IsType<MonitoringStoppedDomainEvent>(item),
            item => Assert.IsType<RecordingStartedDomainEvent>(item),
            item => Assert.IsType<RecordingStoppedDomainEvent>(item),
            item => Assert.IsType<MotionDetectedDomainEvent>(item));
        Assert.Empty(device.DomainEvents);
    }
}
