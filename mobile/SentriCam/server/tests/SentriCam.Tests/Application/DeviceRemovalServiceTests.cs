using SentriCam.Application.Abstractions.Persistence;
using SentriCam.Application.CameraControl;
using SentriCam.Application.Common;
using SentriCam.Application.Connections;
using SentriCam.Application.Devices;
using SentriCam.Application.LiveView;
using SentriCam.Application.Monitoring;
using SentriCam.Contracts.CameraControl;
using SentriCam.Contracts.LiveView;
using SentriCam.Contracts.Monitoring;
using SentriCam.Contracts.SignalR;
using SentriCam.Domain.Devices;
using SentriCam.Domain.Recordings;
using SentriCam.Tests.TestDoubles;

namespace SentriCam.Tests.Application;

public sealed class DeviceRemovalServiceTests
{
    [Fact]
    public async Task ImpactIsACompleteConfirmationContractAndRecordingBlocksRemoval()
    {
        var fixture = new Fixture(recording: true);

        var impact = await fixture.Service.GetImpactAsync(fixture.Device.Id.Value, TestContext.Current.CancellationToken);

        Assert.Equal("Front Door", impact.DeviceName);
        Assert.Equal("Google", impact.Manufacturer);
        Assert.Equal("Pixel 9", impact.Model);
        Assert.False(impact.CanRemove);
        Assert.Contains(impact.Conflicts, conflict =>
            conflict.Code == DeviceRemovalConflictCodes.RecordingActive && conflict.BlocksRemoval);
        Assert.True(impact.Retention.UploadedRecordingsPreserved);
        Assert.True(impact.Retention.RecordingMetadataPreserved);
        Assert.True(impact.Retention.AuditHistoryPreserved);
        Assert.True(impact.Retention.HistoricalEventsPreserved);

        await Assert.ThrowsAsync<ResourceConflictException>(() => fixture.Service.RemoveAsync(
            fixture.Device.Id.Value,
            new(true, fixture.Device.DisplayName),
            new("admin-1"),
            TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task ConfirmedRemovalRevokesDisconnectsResolvesReleasesAndAuditsWithoutDeletingRecordings()
    {
        var fixture = new Fixture(cameraCommandsResolved: 1);
        var command = DeviceCommand.Queue(
            fixture.Device.Id,
            DeviceCommandKind.GetStatus,
            "active-command",
            fixture.Clock.GetUtcNow());
        command.MarkDispatched(fixture.Clock.GetUtcNow());
        fixture.Commands.Commands.Add(command);
        var recording = Recording.Create(
            Guid.NewGuid(),
            fixture.Device.Id,
            "retained-client-recording",
            "session-1",
            "retained.mp4",
            "video/mp4",
            1_000,
            1_024,
            fixture.Clock.GetUtcNow().AddMinutes(-1),
            fixture.Clock.GetUtcNow(),
            isMotion: false,
            isManual: true,
            new string('a', 64),
            "retained/recording.mp4");
        fixture.Recordings.Recordings.Add(recording);
        await fixture.Live.CreateAsync(
            new(fixture.Device.Id.Value, LiveViewProtocol.ImplementedQuality),
            new("admin-1", "operator-1"),
            TestContext.Current.CancellationToken);

        var impact = await fixture.Service.GetImpactAsync(fixture.Device.Id.Value, TestContext.Current.CancellationToken);
        var result = await fixture.Service.RemoveAsync(
            fixture.Device.Id.Value,
            new(true, fixture.Device.DisplayName),
            new("admin-1"),
            TestContext.Current.CancellationToken);

        Assert.True(impact.CanRemove);
        Assert.Contains(impact.Conflicts, conflict => conflict.Code == DeviceRemovalConflictCodes.LiveActive);
        Assert.Contains(impact.Conflicts, conflict => conflict.Code == DeviceRemovalConflictCodes.CommandExecuting);
        Assert.False(fixture.Device.IsEnabled);
        Assert.Equal(DeviceStatus.Disabled, fixture.Device.Status);
        Assert.Equal(DeviceCommandState.Failed, command.State);
        Assert.Equal("device_removed", command.FailureReason);
        Assert.False(fixture.Live.HasActiveSession(fixture.Device.Id.Value));
        Assert.Equal("connection-1", fixture.Realtime.RevokedConnectionId);
        Assert.Null(fixture.Connections.GetStatus(fixture.Device.Id));
        var audit = Assert.Single(fixture.Events.Values);
        Assert.Equal("device.unpaired", audit.EventType);
        Assert.Contains("admin-1", audit.PayloadJson);
        Assert.Single(fixture.Recordings.Recordings);
        Assert.Same(recording, fixture.Recordings.Recordings[0]);
        Assert.True(result.CredentialsRevoked);
        Assert.True(result.RealtimeDisconnected);
        Assert.True(result.LiveSessionReleased);
        Assert.Equal(2, result.CommandsResolved);
        Assert.True(result.UploadedRecordingsPreserved);
    }

    [Fact]
    public async Task ConfirmationMustMatchCurrentDeviceName()
    {
        var fixture = new Fixture();

        await Assert.ThrowsAsync<SentriCam.Domain.Common.DomainValidationException>(() =>
            fixture.Service.RemoveAsync(
                fixture.Device.Id.Value,
                new(true, "Another device"),
                new("admin-1"),
                TestContext.Current.CancellationToken));

        Assert.True(fixture.Device.IsEnabled);
        Assert.Empty(fixture.Events.Values);
    }

    private sealed class Fixture
    {
        public Fixture(bool recording = false, int cameraCommandsResolved = 0)
        {
            Devices.Devices.Add(Device);
            Connections.Connected(Device.Id, "connection-1", Clock.GetUtcNow());
            ConnectionRepository.Connections.Add(DeviceConnection.Connect(
                Device.Id,
                "connection-1",
                Clock.GetUtcNow()));
            Camera = new FakeCameraControlEngine(Device.Id.Value, recording, cameraCommandsResolved);
            Live = new LiveSessionEngine(Connections, new NullLiveViewTransport(), Clock);
            Live.ReportCapabilities(
                Device.Id.Value,
                "connection-1",
                new LiveDeviceCapabilities(
                    Device.Id.Value,
                    [new LiveCameraCapability("back", true, false, false, [], [])],
                    true,
                    Clock.GetUtcNow(),
                    LiveReadinessStates.Ready));
            Service = new DeviceRemovalService(
                Devices,
                Commands,
                ConnectionRepository,
                Events,
                Camera,
                Live,
                Connections,
                new DeviceOperationalHealthRegistry(),
                Realtime,
                new FakeMonitoringEventPublisher(),
                new FakeUnitOfWork(),
                Clock);
        }

        public Device Device { get; } = DeviceTestFactory.CreateDevice();
        public MutableTimeProvider Clock { get; } = new(DeviceTestFactory.Now);
        public FakeDeviceRepository Devices { get; } = new();
        public FakeCommandRepository Commands { get; } = new();
        public FakeDeviceConnectionRepository ConnectionRepository { get; } = new();
        public FakeRecordingRepository Recordings { get; } = new();
        public FakeDeviceEventRepository Events { get; } = new();
        public DeviceConnectionManager Connections { get; } = new(new MutableTimeProvider(DeviceTestFactory.Now));
        public CapturingRealtimeControl Realtime { get; } = new();
        public FakeCameraControlEngine Camera { get; }
        public LiveSessionEngine Live { get; }
        public DeviceRemovalService Service { get; }
    }

    private sealed class FakeDeviceEventRepository : IDeviceEventRepository
    {
        public List<DeviceEvent> Values { get; } = [];
        public void Add(DeviceEvent deviceEvent) => Values.Add(deviceEvent);
    }

    private sealed class CapturingRealtimeControl : IDeviceRealtimeControl
    {
        public string? RevokedConnectionId { get; private set; }
        public Task<bool> RevokeAndDisconnectAsync(
            string connectionId,
            DevicePairingRevoked revocation,
            CancellationToken cancellationToken = default)
        {
            RevokedConnectionId = connectionId;
            return Task.FromResult(true);
        }
    }

    private sealed class FakeCameraControlEngine(
        Guid deviceId,
        bool recording,
        int commandsResolved) : ICameraControlEngine
    {
        public Task<CameraControlCenterView> GetAsync(Guid requestedDeviceId, CancellationToken cancellationToken = default) =>
            Task.FromResult(new CameraControlCenterView(
                deviceId,
                true,
                CameraControlSettings.Default,
                new CameraControlDeviceReport(
                    deviceId,
                    CameraControlSettings.Default,
                    [],
                    new CameraControlTelemetry(true, false, recording, false, null, null, null, null, "good", true, true),
                    DeviceTestFactory.Now),
                commandsResolved > 0
                    ? [new CameraControlCommandView(
                        Guid.NewGuid(), deviceId, CameraControlIds.Zoom, new(Number: 1.1),
                        CameraControlCommandState.Executing, 1, true, true, "command", "admin", null,
                        DeviceTestFactory.Now, null)]
                    : [],
                DeviceTestFactory.Now));

        public Task<int> ResolveForDeviceRemovalAsync(Guid requestedDeviceId, CameraControlActor actor, CancellationToken cancellationToken = default) => Task.FromResult(commandsResolved);
        public Task<CameraControlCommandView> SubmitAsync(Guid requestedDeviceId, CameraControlCommandRequest request, CameraControlActor actor, CancellationToken cancellationToken = default) => throw new NotSupportedException();
        public Task<CameraControlCommandView> CancelAsync(Guid requestedDeviceId, Guid commandId, CameraControlActor actor, CancellationToken cancellationToken = default) => throw new NotSupportedException();
        public Task ReportAsync(Guid authenticatedDeviceId, string connectionId, CameraControlDeviceReport report, CancellationToken cancellationToken = default) => throw new NotSupportedException();
        public Task CompleteAsync(Guid authenticatedDeviceId, string connectionId, CameraControlCommandResult result, CancellationToken cancellationToken = default) => throw new NotSupportedException();
    }
}
