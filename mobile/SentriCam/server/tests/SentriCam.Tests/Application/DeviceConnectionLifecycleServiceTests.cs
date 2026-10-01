using System.Text.Json;
using SentriCam.Application.Common;
using SentriCam.Application.Connections;
using SentriCam.Contracts.SignalR;
using SentriCam.Domain.Devices;
using SentriCam.Tests.TestDoubles;
using Microsoft.Extensions.Logging.Abstractions;
using SentriCam.Application.Monitoring;

namespace SentriCam.Tests.Application;

public sealed class DeviceConnectionLifecycleServiceTests
{
    [Fact]
    public async Task ConnectAndReconnectPersistOneActiveConnection()
    {
        var fixture = CreateFixture();

        var first = await fixture.Service.ConnectedAsync(
            fixture.Device.Id.Value,
            "connection-1",
            TestContext.Current.CancellationToken);
        var duplicate = await fixture.Service.ConnectedAsync(
            fixture.Device.Id.Value,
            "connection-1",
            TestContext.Current.CancellationToken);
        var replacement = await fixture.Service.ConnectedAsync(
            fixture.Device.Id.Value,
            "connection-2",
            TestContext.Current.CancellationToken);

        Assert.False(first.IsReconnect);
        Assert.False(duplicate.IsReconnect);
        Assert.True(replacement.IsReconnect);
        Assert.Null(first.ReplacedConnectionId);
        Assert.Null(duplicate.ReplacedConnectionId);
        Assert.Equal("connection-1", replacement.ReplacedConnectionId);
        Assert.Equal(2, fixture.Connections.Connections.Count);
        Assert.Single(fixture.Connections.Connections, connection => connection.IsActive);
        Assert.Equal("connection-2", fixture.Connections.Connections.Single(connection => connection.IsActive).TransportConnectionId);
    }

    [Fact]
    public async Task HeartbeatPersistsExistingSnapshotOnceAndAcknowledgesServerTime()
    {
        var fixture = CreateFixture();
        await fixture.Service.ConnectedAsync(
            fixture.Device.Id.Value,
            "connection-1",
            TestContext.Current.CancellationToken);
        var heartbeat = CreateHeartbeat(fixture.Device.Id.Value, "connection-1", 42);

        var acknowledgement = await fixture.Service.HeartbeatAsync(
            heartbeat,
            "connection-1",
            TestContext.Current.CancellationToken);
        await fixture.Service.HeartbeatAsync(
            heartbeat,
            "connection-1",
            TestContext.Current.CancellationToken);

        var snapshot = Assert.Single(fixture.Snapshots.Snapshots);
        Assert.Equal(42, snapshot.SnapshotVersion);
        Assert.Equal(DeviceStatus.Monitoring, snapshot.Status);
        Assert.Equal(76, snapshot.BatteryPercentage);
        Assert.Equal(8_192, snapshot.AvailableStorageBytes);
        Assert.Equal(fixture.Now, acknowledgement.ServerUtcNow);
        Assert.Equal("connection-1", acknowledgement.ConnectionId);
        Assert.Equal(DeviceStatus.Monitoring, fixture.Device.Status);
    }

    [Fact]
    public async Task HeartbeatRejectsMismatchedTransportConnection()
    {
        var fixture = CreateFixture();
        await fixture.Service.ConnectedAsync(
            fixture.Device.Id.Value,
            "connection-1",
            TestContext.Current.CancellationToken);

        await Assert.ThrowsAsync<ArgumentException>(() => fixture.Service.HeartbeatAsync(
            CreateHeartbeat(fixture.Device.Id.Value, "connection-1", 1),
            "connection-2",
            TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task StaleDisconnectDoesNotMarkReplacementOffline()
    {
        var fixture = CreateFixture();
        await fixture.Service.ConnectedAsync(
            fixture.Device.Id.Value,
            "connection-1",
            TestContext.Current.CancellationToken);
        await fixture.Service.ConnectedAsync(
            fixture.Device.Id.Value,
            "connection-2",
            TestContext.Current.CancellationToken);

        await fixture.Service.DisconnectedAsync(
            "connection-1",
            TestContext.Current.CancellationToken);
        Assert.Equal(DeviceStatus.Online, fixture.Device.Status);

        await fixture.Service.DisconnectedAsync(
            "connection-2",
            TestContext.Current.CancellationToken);
        Assert.Equal(DeviceStatus.Online, fixture.Device.Status);
    }

    [Fact]
    public async Task ExplicitDisconnectPublishesRecoveringTransitionExactlyOnce()
    {
        var fixture = CreateFixture();
        await fixture.Service.ConnectedAsync(
            fixture.Device.Id.Value,
            "connection-1",
            TestContext.Current.CancellationToken);

        await fixture.Service.DisconnectedAsync(
            "connection-1",
            TestContext.Current.CancellationToken);
        await fixture.Service.DisconnectedAsync(
            "connection-1",
            TestContext.Current.CancellationToken);

        Assert.Equal(2, fixture.Publisher.DeviceUpdates.Count);
    }

    [Fact]
    public async Task AuthenticationScopeIsEnforcedByApplicationService()
    {
        var fixture = CreateFixture();
        var anotherDevice = DeviceTestFactory.CreateDevice("another-installation");
        fixture.Devices.Devices.Add(anotherDevice);

        await Assert.ThrowsAsync<InvalidOperationException>(() => fixture.Service.ConnectedAsync(
            anotherDevice.Id.Value,
            "connection-unauthorized",
            TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task ReconnectDispatchesAStillValidPendingCommand()
    {
        var fixture = CreateFixture();
        var command = DeviceCommand.Queue(
            fixture.Device.Id,
            DeviceCommandKind.GetStatus,
            "recover-after-reconnect",
            fixture.Now,
            fixture.Now.AddSeconds(30),
            DeviceCommandOrigin.Operator);
        fixture.Commands.Add(command);

        await fixture.Service.ConnectedAsync(
            fixture.Device.Id.Value,
            "connection-recovered",
            TestContext.Current.CancellationToken);

        Assert.Equal(DeviceCommandState.Dispatched, command.State);
        Assert.Single(fixture.Transport.Dispatched);
    }

    private static DeviceHeartbeat CreateHeartbeat(Guid deviceId, string connectionId, long version)
    {
        using var document = JsonDocument.Parse("""
            {
              "monitoringEnabled": true,
              "recordingState": "IDLE",
              "battery": { "levelPercent": 76 },
              "storage": { "availableBytes": 8192 },
              "schemaVersion": 1
            }
            """);
        return new DeviceHeartbeat(
            deviceId,
            connectionId,
            version,
            DeviceTestFactory.Now,
            document.RootElement.Clone());
    }

    private static Fixture CreateFixture()
    {
        var device = DeviceTestFactory.CreateDevice();
        var devices = new FakeDeviceRepository();
        devices.Devices.Add(device);
        var connections = new FakeDeviceConnectionRepository();
        var snapshots = new FakeDeviceSnapshotRepository();
        var unitOfWork = new FakeUnitOfWork();
        var manager = new DeviceConnectionManager(new FixedTimeProvider(DeviceTestFactory.Now));
        var commands = new FakeCommandRepository();
        var transport = new FakeDeviceCommandTransport(true);
        var publisher = new FakeMonitoringEventPublisher();
        var service = new DeviceConnectionLifecycleService(
            devices,
            connections,
            snapshots,
            commands,
            transport,
            unitOfWork,
            new FakeDeviceRequestAuthorizer(device.Id),
            manager,
            new DevicePresenceTransitionTracker(),
            publisher,
            new FixedTimeProvider(DeviceTestFactory.Now),
            NullLogger<DeviceConnectionLifecycleService>.Instance);
        return new Fixture(
            device,
            devices,
            connections,
            snapshots,
            commands,
            transport,
            publisher,
            service,
            DeviceTestFactory.Now);
    }

    private sealed record Fixture(
        Device Device,
        FakeDeviceRepository Devices,
        FakeDeviceConnectionRepository Connections,
        FakeDeviceSnapshotRepository Snapshots,
        FakeCommandRepository Commands,
        FakeDeviceCommandTransport Transport,
        FakeMonitoringEventPublisher Publisher,
        DeviceConnectionLifecycleService Service,
        DateTimeOffset Now);
}
