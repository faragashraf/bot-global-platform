using SentriCam.Application.Connections;
using SentriCam.Application.LiveView;
using SentriCam.Contracts.LiveView;
using SentriCam.Domain.Devices;

namespace SentriCam.Tests.Application;

public sealed class LiveSessionEngineTests
{
    private static readonly Guid DeviceId = Guid.Parse("11111111-1111-1111-1111-111111111111");
    private static readonly Guid SecondDeviceId = Guid.Parse("22222222-2222-2222-2222-222222222222");
    private static readonly LiveSessionOwner Owner = new("local-operator", "operator-1");

    [Fact]
    public async Task CreateRequiresAnOnlineAuthenticatedDevice()
    {
        var fixture = new Fixture(connectDevice: false);

        var failure = await Assert.ThrowsAsync<LiveSessionException>(() =>
            fixture.Engine.CreateAsync(new(DeviceId, "medium"), Owner, Token));

        Assert.Equal("device_offline", failure.Code);
        Assert.Empty(fixture.Transport.Assignments);
    }

    [Fact]
    public async Task CreateAllowsOnlyTheImplementedMediumQuality()
    {
        var fixture = new Fixture();

        var failure = await Assert.ThrowsAsync<LiveSessionException>(() =>
            fixture.Engine.CreateAsync(new(DeviceId, "high"), Owner, Token));

        Assert.Equal("quality_not_available", failure.Code);
        var availability = fixture.Engine.GetAvailability(DeviceId);
        Assert.Single(availability.QualityOptions, option => option.Available);
        Assert.Equal("medium", availability.QualityOptions.Single(option => option.Available).Id);
    }

    [Fact]
    public async Task CreateEnforcesOneActiveSessionPerDeviceAndAssignsTheExactConnection()
    {
        var fixture = new Fixture();

        var session = await fixture.Engine.CreateAsync(new(DeviceId, "medium"), Owner, Token);
        var failure = await Assert.ThrowsAsync<LiveSessionException>(() =>
            fixture.Engine.CreateAsync(new(DeviceId, "medium"), new("second", "operator-2"), Token));

        Assert.Equal(LiveSessionStates.Connecting, session.State);
        Assert.Equal("session_conflict", failure.Code);
        var assignment = Assert.Single(fixture.Transport.Assignments);
        Assert.Equal("device-1", assignment.ConnectionId);
        Assert.Equal(session.SessionId, assignment.Value.Session.SessionId);
    }

    [Fact]
    public async Task BrowserDispatchIsNotDevicePublisherAcknowledgement()
    {
        var fixture = new Fixture();
        var session = await fixture.Engine.CreateAsync(new(DeviceId, "medium"), Owner, Token);

        await fixture.Engine.SubmitOfferAsync(
            new(session.SessionId, "offer", "v=0 browser"),
            Owner,
            Token);

        Assert.False(session.DeviceAcknowledged);
        Assert.False(fixture.Transport.SessionChanges[^1].Value.DeviceAcknowledged);

        await fixture.Engine.ReportDeviceStateAsync(
            DeviceId,
            "device-1",
            new(session.SessionId, LiveSessionStates.Connecting, null),
            Token);

        Assert.True(fixture.Transport.SessionChanges[^1].Value.DeviceAcknowledged);
    }

    [Fact]
    public async Task DifferentDevicesOwnIndependentConcurrentSessions()
    {
        var fixture = new Fixture(connectSecondDevice: true);

        var first = await fixture.Engine.CreateAsync(new(DeviceId, "medium"), Owner, Token);
        var second = await fixture.Engine.CreateAsync(new(SecondDeviceId, "medium"), Owner, Token);

        Assert.NotEqual(first.SessionId, second.SessionId);
        Assert.Equal(2, fixture.Transport.Assignments.Count);
        Assert.Contains(fixture.Transport.Assignments, assignment =>
            assignment.ConnectionId == "device-1" && assignment.Value.Session.SessionId == first.SessionId);
        Assert.Contains(fixture.Transport.Assignments, assignment =>
            assignment.ConnectionId == "device-2" && assignment.Value.Session.SessionId == second.SessionId);
        var metrics = fixture.Engine.GetMetrics();
        Assert.Equal(1, metrics.ActiveViewerCount);
        Assert.Equal(2, metrics.ActiveTileSessions);
        Assert.Equal(2, metrics.ActivePublishers);
    }

    [Fact]
    public async Task ClosingOneTileDoesNotInterruptAnotherDeviceSession()
    {
        var fixture = new Fixture(connectSecondDevice: true);
        var first = await fixture.Engine.CreateAsync(new(DeviceId, "medium"), Owner, Token);
        var second = await fixture.Engine.CreateAsync(new(SecondDeviceId, "medium"), Owner, Token);

        await fixture.Engine.CloseAsync(first.SessionId, Owner, Token);
        await fixture.Engine.SubmitOfferAsync(new(second.SessionId, "offer", "v=0"), Owner, Token);

        Assert.Single(fixture.Transport.Ended, ended => ended.Value.SessionId == first.SessionId);
        Assert.Contains(fixture.Transport.DeviceOffers, offer => offer.Value.SessionId == second.SessionId);
        Assert.True(fixture.Engine.HasActiveSession(SecondDeviceId));
    }

    [Fact]
    public async Task DeviceRemovalCleansOnlyTheRemovedCameraSession()
    {
        var fixture = new Fixture(connectSecondDevice: true);
        var removed = await fixture.Engine.CreateAsync(new(DeviceId, "medium"), Owner, Token);
        var retained = await fixture.Engine.CreateAsync(new(SecondDeviceId, "medium"), Owner, Token);

        var closed = await fixture.Engine.CloseForDeviceAsync(DeviceId, Token);

        Assert.True(closed);
        Assert.Single(fixture.Transport.Ended, item =>
            item.Value.SessionId == removed.SessionId && item.Value.ErrorCode == "device_removed");
        await fixture.Engine.SubmitOfferAsync(new(retained.SessionId, "offer", "v=0"), Owner, Token);
        Assert.True(fixture.Engine.HasActiveSession(SecondDeviceId));
    }

    [Fact]
    public async Task HubRestartBeginsWithNoPersistedOrOrphanedLiveSessions()
    {
        var fixture = new Fixture();
        await fixture.Engine.CreateAsync(new(DeviceId, "medium"), Owner, Token);

        var restarted = new LiveSessionEngine(fixture.Connections, new CapturingTransport(), fixture.Clock);

        Assert.False(restarted.HasActiveSession(DeviceId));
        Assert.Equal(0, restarted.GetMetrics().ActiveTileSessions);
        restarted.ReportCapabilities(DeviceId, "device-1", Capability(DeviceId, fixture.Clock.GetUtcNow()));
        var replacement = await restarted.CreateAsync(new(DeviceId, "medium"), Owner, Token);
        Assert.Equal(LiveSessionStates.Connecting, replacement.State);
    }

    [Fact]
    public async Task OperatorDisconnectCleansEveryOwnedTileSession()
    {
        var fixture = new Fixture(connectSecondDevice: true);
        await fixture.Engine.CreateAsync(new(DeviceId, "medium"), Owner, Token);
        await fixture.Engine.CreateAsync(new(SecondDeviceId, "medium"), Owner, Token);

        await fixture.Engine.CloseForOperatorConnectionAsync(Owner.ConnectionId, Token);

        Assert.Equal(2, fixture.Transport.Ended.Count);
        Assert.False(fixture.Engine.HasActiveSession(DeviceId));
        Assert.False(fixture.Engine.HasActiveSession(SecondDeviceId));
    }

    [Fact]
    public async Task StaleSessionGenerationCannotUpdateItsReplacement()
    {
        var fixture = new Fixture();
        var stale = await fixture.Engine.CreateAsync(new(DeviceId, "medium"), Owner, Token);
        await fixture.Engine.CloseAsync(stale.SessionId, Owner, Token);
        var current = await fixture.Engine.CreateAsync(new(DeviceId, "medium"), Owner, Token);

        var failure = await Assert.ThrowsAsync<LiveSessionException>(() =>
            fixture.Engine.SubmitOfferAsync(new(stale.SessionId, "offer", "v=0 stale"), Owner, Token));

        Assert.Equal("session_not_found", failure.Code);
        await fixture.Engine.SubmitOfferAsync(new(current.SessionId, "offer", "v=0 current"), Owner, Token);
        Assert.Equal(current.SessionId, fixture.Transport.DeviceOffers[^1].Value.SessionId);
    }

    [Fact]
    public async Task FailedDeviceAssignmentReleasesTheSingleSessionSlot()
    {
        var fixture = new Fixture();
        fixture.Transport.FailAssignment = true;

        var failure = await Assert.ThrowsAsync<LiveSessionException>(() =>
            fixture.Engine.CreateAsync(new(DeviceId, "medium"), Owner, Token));
        fixture.Transport.FailAssignment = false;
        fixture.Engine.ReportCapabilities(DeviceId, "device-1", Capability(DeviceId, fixture.Clock.GetUtcNow()));
        var replacement = await fixture.Engine.CreateAsync(new(DeviceId, "medium"), Owner, Token);

        Assert.Equal("device_unavailable", failure.Code);
        Assert.Equal(LiveSessionStates.Connecting, replacement.State);
    }

    [Fact]
    public async Task OfferAnswerAndCandidatesAreRelayedOnlyAsSignaling()
    {
        var fixture = new Fixture();
        var session = await fixture.Engine.CreateAsync(new(DeviceId, "medium"), Owner, Token);
        var offer = new LiveSessionDescription(session.SessionId, "offer", "v=0\r\no=browser");
        var answer = new LiveSessionDescription(session.SessionId, "answer", "v=0\r\no=android");
        var browserCandidate = new LiveIceCandidate(session.SessionId, "candidate:browser", "0", 0, null);
        var deviceCandidate = new LiveIceCandidate(session.SessionId, "candidate:device", "0", 0, null);

        await fixture.Engine.SubmitOfferAsync(offer, Owner, Token);
        await fixture.Engine.SubmitAnswerAsync(DeviceId, "device-1", answer, Token);
        await fixture.Engine.SubmitOperatorCandidateAsync(browserCandidate, Owner, Token);
        await fixture.Engine.SubmitDeviceCandidateAsync(DeviceId, "device-1", deviceCandidate, Token);

        Assert.Equal(offer, Assert.Single(fixture.Transport.DeviceOffers).Value);
        Assert.Equal(answer, Assert.Single(fixture.Transport.OperatorAnswers).Value);
        Assert.Equal(browserCandidate, Assert.Single(fixture.Transport.DeviceCandidates).Value);
        Assert.Equal(deviceCandidate, Assert.Single(fixture.Transport.OperatorCandidates).Value);
    }

    [Fact]
    public async Task SessionOwnerAndDeviceConnectionAreBothEnforced()
    {
        var fixture = new Fixture();
        var session = await fixture.Engine.CreateAsync(new(DeviceId, "medium"), Owner, Token);

        var ownerFailure = await Assert.ThrowsAsync<LiveSessionException>(() =>
            fixture.Engine.CloseAsync(session.SessionId, new("local-operator", "other-tab"), Token));
        var deviceFailure = await Assert.ThrowsAsync<LiveSessionException>(() =>
            fixture.Engine.SubmitAnswerAsync(
                DeviceId,
                "replaced-device-connection",
                new(session.SessionId, "answer", "v=0"),
                Token));

        Assert.Equal("session_not_owned", ownerFailure.Code);
        Assert.Equal("device_not_owned", deviceFailure.Code);
    }

    [Fact]
    public async Task PreviewVisibilityDoesNotCloseOrReplaceTheStream()
    {
        var fixture = new Fixture();
        var session = await fixture.Engine.CreateAsync(new(DeviceId, "medium"), Owner, Token);

        await fixture.Engine.SetPreviewVisibilityAsync(session.SessionId, false, Owner, Token);
        await fixture.Engine.SubmitOfferAsync(new(session.SessionId, "offer", "v=0"), Owner, Token);

        var visibility = Assert.Single(fixture.Transport.PreviewChanges).Value;
        Assert.False(visibility.Visible);
        Assert.Equal(session.SessionId, Assert.Single(fixture.Transport.DeviceOffers).Value.SessionId);
    }

    [Fact]
    public async Task ConnectedSessionIsClosedAfterActivityTimeout()
    {
        var fixture = new Fixture();
        var session = await fixture.Engine.CreateAsync(new(DeviceId, "medium"), Owner, Token);
        await fixture.Engine.ReportDeviceStateAsync(
            DeviceId,
            "device-1",
            new(session.SessionId, LiveSessionStates.Connected, null),
            Token);
        fixture.Clock.Advance(TimeSpan.FromSeconds(31));

        var expired = await fixture.Engine.ExpireAsync(Token);

        Assert.Equal(1, expired);
        Assert.Equal("session_timeout", Assert.Single(fixture.Transport.Ended).Value.ErrorCode);
        fixture.Connections.Heartbeat(
            SentriCam.Domain.Devices.DeviceId.From(DeviceId),
            "device-1",
            fixture.Clock.GetUtcNow());
        fixture.Engine.ReportCapabilities(DeviceId, "device-1", Capability(DeviceId, fixture.Clock.GetUtcNow()));
        var replacement = await fixture.Engine.CreateAsync(new(DeviceId, "medium"), Owner, Token);
        Assert.NotEqual(session.SessionId, replacement.SessionId);
    }

    [Fact]
    public async Task DisconnectRecoversSessionOnlyForTheBoundDeviceConnection()
    {
        var fixture = new Fixture();
        var session = await fixture.Engine.CreateAsync(new(DeviceId, "medium"), Owner, Token);

        await fixture.Engine.CloseForDeviceConnectionAsync(DeviceId, "stale-device", Token);
        await fixture.Engine.SubmitOfferAsync(new(session.SessionId, "offer", "v=0"), Owner, Token);
        await fixture.Engine.CloseForDeviceConnectionAsync(DeviceId, "device-1", Token);
        Assert.Empty(fixture.Transport.Ended);

        await fixture.Engine.RecoverForDeviceConnectionAsync(DeviceId, "device-2", Token);
        await fixture.Engine.SubmitAnswerAsync(
            DeviceId,
            "device-2",
            new(session.SessionId, "answer", "v=0"),
            Token);

        Assert.Equal(2, fixture.Transport.Assignments.Count);
        Assert.Contains(fixture.Transport.Assignments, item => item.ConnectionId == "device-2");
    }

    [Fact]
    public async Task ReplacementConnectionAdoptsSessionBeforeStaleDisconnectArrives()
    {
        var fixture = new Fixture();
        var session = await fixture.Engine.CreateAsync(new(DeviceId, "medium"), Owner, Token);
        await fixture.Engine.ReportDeviceStateAsync(
            DeviceId,
            "device-1",
            new(session.SessionId, LiveSessionStates.Connecting, null),
            Token);
        Assert.True(fixture.Transport.SessionChanges[^1].Value.DeviceAcknowledged);

        fixture.Connections.Connected(
            SentriCam.Domain.Devices.DeviceId.From(DeviceId),
            "device-2",
            fixture.Clock.GetUtcNow());
        await fixture.Engine.RecoverForDeviceConnectionAsync(DeviceId, "device-2", Token);
        await fixture.Engine.CloseForDeviceConnectionAsync(DeviceId, "device-1", Token);
        await fixture.Engine.SubmitAnswerAsync(
            DeviceId,
            "device-2",
            new(session.SessionId, "answer", "v=0 replacement"),
            Token);

        Assert.Equal(2, fixture.Transport.Assignments.Count);
        Assert.Equal("device-2", fixture.Transport.Assignments[^1].ConnectionId);
        Assert.False(fixture.Transport.Assignments[^1].Value.Session.DeviceAcknowledged);
        Assert.Empty(fixture.Transport.Ended);
        Assert.True(fixture.Engine.HasActiveSession(DeviceId));
    }

    [Fact]
    public void CapabilityReportMustBelongToTheAuthenticatedDevice()
    {
        var fixture = new Fixture();
        var report = new LiveDeviceCapabilities(
            Guid.NewGuid(),
            [new("back", true, true, true, ["1280x720"], [30])],
            true,
            fixture.Clock.GetUtcNow());

        Assert.Throws<SentriCam.Application.Common.AccessDeniedException>(() =>
            fixture.Engine.ReportCapabilities(DeviceId, "device-1", report));
    }

    [Fact]
    public async Task CurrentHeartbeatWithoutCurrentConnectionCapabilitiesIsNotLiveReady()
    {
        var fixture = new Fixture();
        fixture.Connections.Connected(
            SentriCam.Domain.Devices.DeviceId.From(DeviceId),
            "device-2",
            fixture.Clock.GetUtcNow());

        var availability = fixture.Engine.GetAvailability(DeviceId);
        var failure = await Assert.ThrowsAsync<LiveSessionException>(() =>
            fixture.Engine.CreateAsync(new(DeviceId, "medium"), Owner, Token));

        Assert.False(availability.Available);
        Assert.Equal(LiveReadinessStates.Initializing, availability.Readiness);
        Assert.Equal("live_capabilities_stale", availability.UnavailableReason);
        Assert.Equal("live_capabilities_stale", failure.Code);
    }

    [Fact]
    public async Task ReplacementConnectionBecomesReadyOnlyAfterItsOwnCapabilityReport()
    {
        var fixture = new Fixture();
        fixture.Connections.Connected(
            SentriCam.Domain.Devices.DeviceId.From(DeviceId),
            "device-2",
            fixture.Clock.GetUtcNow());

        var staleFailure = Assert.Throws<LiveSessionException>(() =>
            fixture.Engine.ReportCapabilities(
                DeviceId,
                "device-1",
                Capability(DeviceId, fixture.Clock.GetUtcNow())));
        fixture.Engine.ReportCapabilities(
            DeviceId,
            "device-2",
            Capability(DeviceId, fixture.Clock.GetUtcNow()));
        var session = await fixture.Engine.CreateAsync(new(DeviceId, "medium"), Owner, Token);

        Assert.Equal("stale_device_connection", staleFailure.Code);
        Assert.Equal(LiveReadinessStates.Starting, fixture.Engine.GetAvailability(DeviceId).Readiness);
        Assert.Equal("device-2", Assert.Single(fixture.Transport.Assignments).ConnectionId);
        Assert.NotEqual(Guid.Empty, session.SessionId);
    }

    [Fact]
    public async Task AndroidStartFailureRetainsExactReasonUntilFreshReadinessReport()
    {
        var fixture = new Fixture();
        var session = await fixture.Engine.CreateAsync(new(DeviceId, "medium"), Owner, Token);

        await fixture.Engine.ReportDeviceStateAsync(
            DeviceId,
            "device-1",
            new(session.SessionId, LiveSessionStates.Failed, "camera_permission_missing"),
            Token);

        var failed = fixture.Engine.GetAvailability(DeviceId);
        Assert.False(failed.Available);
        Assert.Equal(LiveReadinessStates.Failed, failed.Readiness);
        Assert.Equal("camera_permission_missing", failed.UnavailableReason);

        fixture.Engine.ReportCapabilities(
            DeviceId,
            "device-1",
            Capability(DeviceId, fixture.Clock.GetUtcNow()));
        Assert.Equal(LiveReadinessStates.Ready, fixture.Engine.GetAvailability(DeviceId).Readiness);
    }

    [Fact]
    public async Task StatisticsSourceMustMatchTheAuthenticatedPeer()
    {
        var fixture = new Fixture();
        var session = await fixture.Engine.CreateAsync(new(DeviceId, "medium"), Owner, Token);
        var spoofed = new LiveSessionStatistics(
            session.SessionId,
            "device",
            1,
            null,
            30,
            null,
            null,
            0,
            1280,
            720,
            fixture.Clock.GetUtcNow());

        var failure = await Assert.ThrowsAsync<LiveSessionException>(() =>
            fixture.Engine.ReportStatisticsAsync(null, Owner.ConnectionId, spoofed, Token));

        Assert.Equal("invalid_statistics", failure.Code);
    }

    [Fact]
    public async Task BrowserReceiveStatisticsAreForwardedToTheBoundAndroidDevice()
    {
        var fixture = new Fixture();
        var session = await fixture.Engine.CreateAsync(new(DeviceId, "medium"), Owner, Token);
        var statistics = new LiveSessionStatistics(
            session.SessionId, "browser", 4_096, null, 29.5, 8, 1, 0, 1280, 720,
            fixture.Clock.GetUtcNow());

        await fixture.Engine.ReportStatisticsAsync(null, Owner.ConnectionId, statistics, Token);

        var forwarded = Assert.Single(fixture.Transport.BrowserStatistics);
        Assert.Equal("device-1", forwarded.ConnectionId);
        Assert.Same(statistics, forwarded.Value);
        Assert.NotNull(fixture.Engine.GetMetrics().AverageFirstFrameMilliseconds);
    }

    private sealed class Fixture
    {
        public Fixture(bool connectDevice = true, bool connectSecondDevice = false)
        {
            Connections = new DeviceConnectionManager(Clock);
            if (connectDevice)
            {
                Connections.Connected(SentriCam.Domain.Devices.DeviceId.From(DeviceId), "device-1", Clock.GetUtcNow());
            }
            if (connectSecondDevice)
            {
                Connections.Connected(SentriCam.Domain.Devices.DeviceId.From(SecondDeviceId), "device-2", Clock.GetUtcNow());
            }
            Engine = new LiveSessionEngine(Connections, Transport, Clock);
            if (connectDevice)
            {
                Engine.ReportCapabilities(DeviceId, "device-1", Capability(DeviceId, Clock.GetUtcNow()));
            }
            if (connectSecondDevice)
            {
                Engine.ReportCapabilities(SecondDeviceId, "device-2", Capability(SecondDeviceId, Clock.GetUtcNow()));
            }
        }

        public ManualTimeProvider Clock { get; } = new();
        public DeviceConnectionManager Connections { get; }
        public CapturingTransport Transport { get; } = new();
        public LiveSessionEngine Engine { get; }
    }

    private sealed class ManualTimeProvider : TimeProvider
    {
        private DateTimeOffset _now = new(2026, 8, 1, 12, 0, 0, TimeSpan.Zero);
        public override DateTimeOffset GetUtcNow() => _now;
        public void Advance(TimeSpan duration) => _now = _now.Add(duration);
    }

    private sealed class CapturingTransport : ILiveViewTransport
    {
        public bool FailAssignment { get; set; }
        public List<Captured<LiveSessionAssignment>> Assignments { get; } = [];
        public List<Captured<LiveSessionDescription>> DeviceOffers { get; } = [];
        public List<Captured<LiveSessionDescription>> OperatorAnswers { get; } = [];
        public List<Captured<LiveIceCandidate>> DeviceCandidates { get; } = [];
        public List<Captured<LiveIceCandidate>> OperatorCandidates { get; } = [];
        public List<Captured<LivePreviewVisibility>> PreviewChanges { get; } = [];
        public List<Captured<LiveSessionStatusUpdate>> Ended { get; } = [];
        public List<Captured<LiveSessionStatistics>> BrowserStatistics { get; } = [];
        public List<Captured<LiveSessionView>> SessionChanges { get; } = [];

        public Task BeginOnDeviceAsync(string deviceConnectionId, LiveSessionAssignment assignment, CancellationToken cancellationToken = default) { if (FailAssignment) throw new IOException("device unavailable"); Assignments.Add(new(deviceConnectionId, assignment)); return Task.CompletedTask; }
        public Task SendOfferToDeviceAsync(string deviceConnectionId, LiveSessionDescription offer, CancellationToken cancellationToken = default) { DeviceOffers.Add(new(deviceConnectionId, offer)); return Task.CompletedTask; }
        public Task SendAnswerToOperatorAsync(string operatorConnectionId, LiveSessionDescription answer, CancellationToken cancellationToken = default) { OperatorAnswers.Add(new(operatorConnectionId, answer)); return Task.CompletedTask; }
        public Task SendCandidateToDeviceAsync(string deviceConnectionId, LiveIceCandidate candidate, CancellationToken cancellationToken = default) { DeviceCandidates.Add(new(deviceConnectionId, candidate)); return Task.CompletedTask; }
        public Task SendCandidateToOperatorAsync(string operatorConnectionId, LiveIceCandidate candidate, CancellationToken cancellationToken = default) { OperatorCandidates.Add(new(operatorConnectionId, candidate)); return Task.CompletedTask; }
        public Task SetPreviewVisibilityAsync(string deviceConnectionId, LivePreviewVisibility visibility, CancellationToken cancellationToken = default) { PreviewChanges.Add(new(deviceConnectionId, visibility)); return Task.CompletedTask; }
        public Task EndOnDeviceAsync(string deviceConnectionId, LiveSessionStatusUpdate update, CancellationToken cancellationToken = default) { Ended.Add(new(deviceConnectionId, update)); return Task.CompletedTask; }
        public Task SessionChangedAsync(string operatorConnectionId, LiveSessionView session, CancellationToken cancellationToken = default) { SessionChanges.Add(new(operatorConnectionId, session)); return Task.CompletedTask; }
        public Task StatisticsUpdatedAsync(string operatorConnectionId, LiveSessionStatistics statistics, CancellationToken cancellationToken = default) => Task.CompletedTask;
        public Task BrowserStatisticsUpdatedAsync(string deviceConnectionId, LiveSessionStatistics statistics, CancellationToken cancellationToken = default) { BrowserStatistics.Add(new(deviceConnectionId, statistics)); return Task.CompletedTask; }
    }

    private sealed record Captured<T>(string ConnectionId, T Value);

    private static LiveDeviceCapabilities Capability(Guid deviceId, DateTimeOffset reportedAtUtc) => new(
        deviceId,
        [new("back", true, true, true, ["1280x720"], [30])],
        true,
        reportedAtUtc);

    private static CancellationToken Token => TestContext.Current.CancellationToken;
}
