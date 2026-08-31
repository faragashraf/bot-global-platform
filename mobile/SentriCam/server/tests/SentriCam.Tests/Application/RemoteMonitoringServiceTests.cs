using System.Text.Json;
using SentriCam.Application.Commands;
using SentriCam.Application.Connections;
using SentriCam.Application.Monitoring;
using SentriCam.Contracts.Commands;
using SentriCam.Contracts.Monitoring;
using SentriCam.Domain.Devices;
using SentriCam.Tests.TestDoubles;

namespace SentriCam.Tests.Application;

public sealed class RemoteMonitoringServiceTests
{
    [Fact]
    public async Task SubmissionPersistsAndDispatchesToCurrentDeviceConnection()
    {
        var fixture = CreateFixture();

        var result = await fixture.Service.SubmitAsync(
            new PingDevice(fixture.Device.Id.Value, "ping-1"),
            TestContext.Current.CancellationToken);

        Assert.Equal(RemoteCommandState.Pending, result.State);
        Assert.Equal(DeviceCommandState.Dispatched, fixture.Commands.Commands.Single().State);
        Assert.Equal(DeviceCommandType.Ping, Assert.Single(fixture.Transport.Dispatched).CommandType);
        Assert.Single(fixture.Publisher.CommandUpdates);
    }

    [Fact]
    public async Task DeviceResultCompletesCommandIdempotentlyAndPublishesUpdate()
    {
        var fixture = CreateFixture();
        var submitted = await fixture.Service.SubmitAsync(
            new RefreshDeviceStatus(fixture.Device.Id.Value, "status-1"),
            TestContext.Current.CancellationToken);
        var completion = new DeviceCommandResultEnvelope(
            submitted.CommandId,
            fixture.Device.Id.Value,
            DeviceCommandOutcome.Succeeded,
            "status",
            null,
            fixture.Clock.Value.AddSeconds(1));

        await fixture.Service.CompleteAsync(
            fixture.Device.Id.Value,
            completion,
            TestContext.Current.CancellationToken);
        await fixture.Service.CompleteAsync(
            fixture.Device.Id.Value,
            completion,
            TestContext.Current.CancellationToken);

        var command = Assert.Single(fixture.Commands.Commands);
        Assert.Equal(DeviceCommandState.Succeeded, command.State);
        Assert.Equal("status", command.ResultCode);
        Assert.Equal(RemoteCommandState.Succeeded, fixture.Publisher.CommandUpdates.Last().State);
    }

    [Fact]
    public async Task CommandWithoutConnectedTransportRemainsPendingForReconnect()
    {
        var fixture = CreateFixture(dispatchResult: false);

        var result = await fixture.Service.SubmitAsync(
            new StartMonitoring(fixture.Device.Id.Value),
            TestContext.Current.CancellationToken);

        Assert.Equal(RemoteCommandState.Pending, result.State);
        Assert.Null(result.ResultCode);
    }

    [Fact]
    public async Task DispatchedCommandExpiresAsTimeout()
    {
        var fixture = CreateFixture();
        await fixture.Service.SubmitAsync(
            new StopMonitoring(fixture.Device.Id.Value),
            TestContext.Current.CancellationToken);
        fixture.Clock.Value = fixture.Clock.Value.AddSeconds(31);

        var expired = await fixture.Service.ExpireCommandsAsync(
            TestContext.Current.CancellationToken);

        Assert.Equal(1, expired);
        Assert.Equal(DeviceCommandState.TimedOut, fixture.Commands.Commands.Single().State);
        Assert.Equal(RemoteCommandState.Timeout, fixture.Publisher.CommandUpdates.Last().State);
    }

    [Fact]
    public async Task StopMonitoringCompletionDoesNotDisconnectTheDeviceTransport()
    {
        var fixture = CreateFixture();
        fixture.Connections.Connected(
            fixture.Device.Id,
            "connection-1",
            fixture.Clock.Value);
        var submitted = await fixture.Service.SubmitAsync(
            new StopMonitoring(fixture.Device.Id.Value),
            TestContext.Current.CancellationToken);

        await fixture.Service.CompleteAsync(
            fixture.Device.Id.Value,
            new DeviceCommandResultEnvelope(
                submitted.CommandId,
                fixture.Device.Id.Value,
                DeviceCommandOutcome.Succeeded,
                "accepted",
                null,
                fixture.Clock.Value.AddSeconds(1)),
            TestContext.Current.CancellationToken);

        var connection = Assert.IsType<DeviceConnectionStatus>(
            fixture.Connections.GetStatus(fixture.Device.Id));
        Assert.Equal(DeviceTransportState.Connected, connection.State);
        Assert.Null(connection.DisconnectedAtUtc);
        Assert.Equal(DeviceCommandState.Succeeded, fixture.Commands.Commands.Single().State);
    }

    [Fact]
    public async Task HealthyHealthReportDoesNotMaskRecoveringTransport()
    {
        var fixture = CreateFixture();
        fixture.Connections.Connected(fixture.Device.Id, "connection-1", fixture.Clock.Value);
        fixture.HealthRegistry.Report(
            fixture.Device.Id,
            new DeviceOperationalHealthReport(
                fixture.Device.Id.Value,
                HealthyPayload(),
                fixture.Clock.Value));

        fixture.Connections.Disconnected("connection-1", fixture.Clock.Value.AddSeconds(5));

        var devices = await fixture.Service.GetDevicesAsync(TestContext.Current.CancellationToken);
        var device = Assert.Single(devices);

        Assert.Equal("recovering", device.ConnectionState);
        Assert.Equal("recovering", device.Health.Overall);
        Assert.False(device.Health.IsCurrent);
        Assert.Equal("connection_lost", device.Health.OutageCause);
        Assert.All(device.Health.Subsystems, subsystem =>
            Assert.NotEqual("healthy", subsystem.Health));
    }

    [Fact]
    public async Task HeartbeatExpiredDisconnectForcesOfflineAndUnknownSubsystems()
    {
        var fixture = CreateFixture();
        var manager = fixture.Connections;
        manager.Connected(fixture.Device.Id, "connection-1", fixture.Clock.Value);
        fixture.HealthRegistry.Report(
            fixture.Device.Id,
            new DeviceOperationalHealthReport(
                fixture.Device.Id.Value,
                HealthyPayload(),
                fixture.Clock.Value));

        fixture.Clock.Value = fixture.Clock.Value.AddSeconds(26);
        _ = manager.GetStatus(fixture.Device.Id);

        var devices = await fixture.Service.GetDevicesAsync(TestContext.Current.CancellationToken);
        var device = Assert.Single(devices);

        Assert.Equal("offline", device.ConnectionState);
        Assert.Equal("offline", device.Health.Overall);
        Assert.True(device.Health.OutageStartedAtUtc is not null);
        Assert.All(device.Health.Subsystems, subsystem =>
            Assert.NotEqual("healthy", subsystem.Health));
    }

    [Fact]
    public async Task StaleHealthInPresenceWindowStaysRecovering()
    {
        var fixture = CreateFixture();
        fixture.Connections.Connected(fixture.Device.Id, "connection-1", fixture.Clock.Value);
        fixture.HealthRegistry.Report(
            fixture.Device.Id,
            new DeviceOperationalHealthReport(
                fixture.Device.Id.Value,
                HealthyPayload(),
                fixture.Clock.Value.AddSeconds(-90)));

        var devices = await fixture.Service.GetDevicesAsync(TestContext.Current.CancellationToken);
        var device = Assert.Single(devices);

        Assert.Equal("connected", device.ConnectionState);
        Assert.Equal("recovering", device.Health.Overall);
        Assert.False(device.Health.IsCurrent);
        Assert.All(device.Health.Subsystems, subsystem =>
            Assert.NotEqual("healthy", subsystem.Health));
    }

    [Fact]
    public async Task ReconnectedDeviceRemainsRecoveringUntilFreshReportArrives()
    {
        var fixture = CreateFixture();
        fixture.Connections.Connected(fixture.Device.Id, "connection-1", fixture.Clock.Value);
        fixture.HealthRegistry.Report(
            fixture.Device.Id,
            new DeviceOperationalHealthReport(fixture.Device.Id.Value, HealthyPayload(), fixture.Clock.Value));

        fixture.Clock.Value = fixture.Clock.Value.AddSeconds(120);
        fixture.Connections.Disconnected("connection-1", fixture.Clock.Value);
        fixture.Clock.Value = fixture.Clock.Value.AddSeconds(26);
        var disconnected = await fixture.Service.GetDevicesAsync(TestContext.Current.CancellationToken);
        var disconnectedDevice = Assert.Single(disconnected);
        Assert.Equal("offline", disconnectedDevice.Health.Overall);

        fixture.Clock.Value = fixture.Clock.Value.AddSeconds(1);
        fixture.Connections.Connected(
            fixture.Device.Id,
            "connection-2",
            fixture.Clock.Value);

        var recovering = await fixture.Service.GetDevicesAsync(TestContext.Current.CancellationToken);
        var recoveringDevice = Assert.Single(recovering);
        Assert.Equal("recovering", recoveringDevice.Health.Overall);
        Assert.False(recoveringDevice.Health.IsCurrent);

        fixture.HealthRegistry.Report(
            fixture.Device.Id,
            new DeviceOperationalHealthReport(
                fixture.Device.Id.Value,
                HealthyPayload(),
                fixture.Clock.Value.AddSeconds(1)));
        var recovered = await fixture.Service.GetDevicesAsync(TestContext.Current.CancellationToken);
        var healthyDevice = Assert.Single(recovered);

        Assert.Equal("connected", healthyDevice.ConnectionState);
        Assert.Equal("healthy", healthyDevice.Health.Overall);
        Assert.True(healthyDevice.Health.IsCurrent);
    }

    [Fact]
    public async Task FreshHeartbeatSnapshotOverridesOlderRealtimeHealthReport()
    {
        var fixture = CreateFixture();
        fixture.Connections.Connected(fixture.Device.Id, "connection-1", fixture.Clock.Value);
        fixture.HealthRegistry.Report(
            fixture.Device.Id,
            new DeviceOperationalHealthReport(
                fixture.Device.Id.Value,
                HealthyPayload(),
                fixture.Clock.Value));
        fixture.Clock.Value = fixture.Clock.Value.AddSeconds(15);
        fixture.Snapshots.Add(DeviceSnapshot.Capture(
            fixture.Device.Id,
            DeviceStatus.Online,
            80,
            8_192,
            false,
            false,
            $$"""
            {"subsystemHealth": {{HealthyPayload().GetRawText()}}, "schemaVersion": 1}
            """,
            2,
            1,
            fixture.Clock.Value,
            fixture.Clock.Value));
        fixture.Connections.Heartbeat(
            fixture.Device.Id,
            "connection-1",
            fixture.Clock.Value);

        var device = Assert.Single(await fixture.Service.GetDevicesAsync(TestContext.Current.CancellationToken));

        Assert.Equal(fixture.Clock.Value, device.Health.ReportedAtUtc);
        Assert.Equal("healthy", device.Health.Overall);
        Assert.True(device.Health.IsCurrent);
    }

    private static Fixture CreateFixture(bool dispatchResult = true)
    {
        var device = DeviceTestFactory.CreateDevice();
        var devices = new FakeDeviceRepository();
        devices.Add(device);
        var snapshots = new FakeDeviceSnapshotRepository();
        var commands = new FakeCommandRepository();
        var unitOfWork = new FakeUnitOfWork();
        var clock = new MutableTimeProvider(DeviceTestFactory.Now);
        var authorizer = new FakeDeviceRequestAuthorizer(device.Id);
        var healthRegistry = new DeviceOperationalHealthRegistry();
        var handler = new DeviceControlCommandHandler(
            devices,
            commands,
            unitOfWork,
            clock,
            authorizer);
        var transport = new FakeDeviceCommandTransport(dispatchResult);
        var publisher = new FakeMonitoringEventPublisher();
        var connections = new DeviceConnectionManager(clock);
        var service = new RemoteMonitoringService(
            devices,
            snapshots,
            commands,
            handler,
            transport,
            connections,
            healthRegistry,
            authorizer,
            publisher,
            unitOfWork,
            clock);
        return new Fixture(device, snapshots, commands, transport, publisher, connections, healthRegistry, clock, service);
    }

    private static JsonElement HealthyPayload()
    {
        using var document = JsonDocument.Parse("""
            {
              "overall":"HEALTHY",
              "realtime": {"subsystem":"REALTIME","lifecycle":"RUNNING","health":"HEALTHY","reconnectCount":0},
              "live": {"subsystem":"LIVE","lifecycle":"IDLE","health":"HEALTHY","reconnectCount":0},
              "recording": {"subsystem":"RECORDING","lifecycle":"IDLE","health":"HEALTHY","reconnectCount":0},
              "upload": {"subsystem":"UPLOAD","lifecycle":"IDLE","health":"HEALTHY","reconnectCount":0},
              "motion": {"subsystem":"MOTION","lifecycle":"IDLE","health":"HEALTHY","reconnectCount":0},
              "camera": {"subsystem":"CAMERA","lifecycle":"IDLE","health":"HEALTHY","reconnectCount":0},
              "battery": {"subsystem":"BATTERY","lifecycle":"IDLE","health":"HEALTHY","reconnectCount":0},
              "storage": {"subsystem":"STORAGE","lifecycle":"IDLE","health":"HEALTHY","reconnectCount":0},
              "commandQueue": {"subsystem":"COMMANDQUEUE","lifecycle":"IDLE","health":"HEALTHY","reconnectCount":0}
            }
            """);
        return document.RootElement.Clone();
    }

    private sealed record Fixture(
        Device Device,
        FakeDeviceSnapshotRepository Snapshots,
        FakeCommandRepository Commands,
        FakeDeviceCommandTransport Transport,
        FakeMonitoringEventPublisher Publisher,
        DeviceConnectionManager Connections,
        DeviceOperationalHealthRegistry HealthRegistry,
        MutableTimeProvider Clock,
        RemoteMonitoringService Service);
}
