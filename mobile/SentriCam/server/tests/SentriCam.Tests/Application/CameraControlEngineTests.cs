using SentriCam.Application.CameraControl;
using SentriCam.Application.Common;
using SentriCam.Application.Connections;
using SentriCam.Contracts.CameraControl;
using SentriCam.Contracts.Motion;
using SentriCam.Domain.Common;
using SentriCam.Domain.Devices;
using SentriCam.Infrastructure.CameraControl;

namespace SentriCam.Tests.Application;

public sealed class CameraControlEngineTests
{
    private static readonly DateTimeOffset Now = new(2026, 8, 2, 10, 0, 0, TimeSpan.Zero);

    [Fact]
    public async Task CapabilityReportEnablesValidatedCommandAndPersistsAudit()
    {
        var fixture = new Fixture();
        await fixture.ReportAsync(TestContext.Current.CancellationToken);

        var command = await fixture.Engine.SubmitAsync(
            fixture.DeviceId,
            Request(CameraControlIds.Zoom, new CameraControlValue(Number: 2)),
            new CameraControlActor("operator-7"),
            TestContext.Current.CancellationToken);

        Assert.Equal(CameraControlCommandState.Executing, command.State);
        Assert.Single(fixture.Transport.Dispatched);
        var stored = await fixture.Store.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken);
        Assert.Contains(stored!.Audit, entry => entry.Action == "queued" && entry.ActorId == "operator-7");
        Assert.Contains(stored.Audit, entry => entry.Action == "dispatched");
    }

    [Fact]
    public async Task DateTimeOverlayIsDispatchedAsOneConfigurationAndPersistedAfterDeviceAcknowledgement()
    {
        var fixture = new Fixture();
        var report = await fixture.ReportAsync(TestContext.Current.CancellationToken);
        var overlay = new DateTimeOverlayConfiguration(true, true, true, false, DateTimeOverlayPositions.TopRight);

        var command = await fixture.Engine.SubmitAsync(
            fixture.DeviceId,
            Request(CameraControlIds.DateTimeOverlay, new CameraControlValue(DateTimeOverlay: overlay)),
            Actor(),
            TestContext.Current.CancellationToken);

        Assert.Equal(overlay, fixture.Transport.Dispatched.Single().DesiredSettings.DateTimeOverlay);
        await fixture.Engine.CompleteAsync(
            fixture.DeviceId,
            Fixture.ConnectionId,
            Result(command, report with { Settings = report.Settings with { DateTimeOverlay = overlay } }, succeeded: true),
            TestContext.Current.CancellationToken);
        Assert.Equal(overlay, (await fixture.Engine.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken)).DesiredSettings.DateTimeOverlay);
    }

    [Fact]
    public async Task ReplacementConnectionDoesNotExposeStaleCapabilitiesAsCurrent()
    {
        var fixture = new Fixture();
        await fixture.ReportAsync(TestContext.Current.CancellationToken);
        const string replacement = "replacement-device-connection";
        fixture.Connections.Connected(DeviceId.From(fixture.DeviceId), replacement, Now.AddSeconds(1));

        var waiting = await fixture.Engine.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken);
        var blocked = await Assert.ThrowsAsync<ResourceConflictException>(() => fixture.Engine.SubmitAsync(
            fixture.DeviceId,
            Request(CameraControlIds.Recording, new(Text: CameraControlValues.Start)),
            Actor(),
            TestContext.Current.CancellationToken));

        Assert.True(waiting.Online);
        Assert.Null(waiting.DeviceState);
        Assert.Contains("stale", blocked.Message, StringComparison.OrdinalIgnoreCase);

        var fresh = new CameraControlDeviceReport(
            fixture.DeviceId,
            CameraControlSettings.Default,
            [new(CameraControlIds.Recording, true, true, new(Text: "idle"), AllowedValues: ["start", "stop"])],
            new(true, false, false, true, 80, 31, 10_000, false, "good", true, true),
            Now.AddSeconds(1));
        await fixture.Engine.ReportAsync(fixture.DeviceId, replacement, fresh, TestContext.Current.CancellationToken);

        Assert.NotNull((await fixture.Engine.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken)).DeviceState);
    }

    [Fact]
    public async Task CommandsAreSerializedPerCamera()
    {
        var fixture = new Fixture();
        var report = await fixture.ReportAsync(TestContext.Current.CancellationToken);
        var first = await fixture.Engine.SubmitAsync(fixture.DeviceId, Request(CameraControlIds.Zoom, new CameraControlValue(Number: 2)), Actor(), TestContext.Current.CancellationToken);
        var second = await fixture.Engine.SubmitAsync(fixture.DeviceId, Request(CameraControlIds.Torch, new CameraControlValue(Boolean: true)), Actor(), TestContext.Current.CancellationToken);

        Assert.Equal(CameraControlCommandState.Queued, second.State);
        Assert.Single(fixture.Transport.Dispatched);

        await fixture.Engine.CompleteAsync(fixture.DeviceId, Fixture.ConnectionId, Result(first, report, succeeded: true), TestContext.Current.CancellationToken);

        Assert.Equal(2, fixture.Transport.Dispatched.Count);
        Assert.Equal(second.CommandId, fixture.Transport.Dispatched[1].CommandId);
    }

    [Fact]
    public async Task RecordingStartUsesUnifiedLifecyclePublishesStateAndPersistsAudit()
    {
        var fixture = new Fixture();
        var report = await fixture.ReportAsync(TestContext.Current.CancellationToken);

        var command = await fixture.Engine.SubmitAsync(
            fixture.DeviceId,
            Request(CameraControlIds.Recording, new CameraControlValue(Text: CameraControlValues.Start)),
            Actor(),
            TestContext.Current.CancellationToken);

        Assert.Equal(CameraControlCommandState.Executing, command.State);
        Assert.Equal(CameraControlIds.Recording, Assert.Single(fixture.Transport.Dispatched).Control);
        Assert.Equal(CameraControlValues.Start, fixture.Transport.Dispatched.Single().Value.Text);
        var recordingReport = report with
        {
            Telemetry = report.Telemetry with
            {
                Recording = true,
                RecordingState = "recording",
                RecordingOrigin = "remote",
            },
        };
        await fixture.Engine.CompleteAsync(
            fixture.DeviceId,
            Fixture.ConnectionId,
            Result(command, recordingReport, true) with { ResultCode = "recording_start_accepted" },
            TestContext.Current.CancellationToken);

        var view = await fixture.Engine.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken);
        var completed = view.RecentCommands.Single(item => item.CommandId == command.CommandId);
        Assert.Equal(CameraControlCommandState.Succeeded, completed.State);
        Assert.Equal("recording", view.DeviceState!.Telemetry.RecordingState);
        Assert.Equal(CameraControlSettings.Default, view.DesiredSettings);
        var stored = await fixture.Store.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken);
        Assert.Contains(stored!.Audit, entry => entry.CommandId == command.CommandId && entry.Action == "queued");
        Assert.Contains(stored.Audit, entry => entry.CommandId == command.CommandId && entry.Action == "completed");
    }

    [Fact]
    public async Task RecordingStopWhileIdleCanCompleteSafelyWithoutChangingSettings()
    {
        var fixture = new Fixture();
        var report = await fixture.ReportAsync(TestContext.Current.CancellationToken);
        var command = await fixture.Engine.SubmitAsync(
            fixture.DeviceId,
            Request(CameraControlIds.Recording, new CameraControlValue(Text: CameraControlValues.Stop)),
            Actor(),
            TestContext.Current.CancellationToken);

        await fixture.Engine.CompleteAsync(
            fixture.DeviceId,
            Fixture.ConnectionId,
            Result(command, report, true) with { ResultCode = "recording_already_idle" },
            TestContext.Current.CancellationToken);

        var completed = (await fixture.Engine.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken))
            .RecentCommands.Single(item => item.CommandId == command.CommandId);
        Assert.Equal(CameraControlCommandState.Succeeded, completed.State);
        Assert.Equal("recording_already_idle", completed.ResultCode);
    }

    [Fact]
    public async Task TransientDeviceFailureRetriesUpToSharedPipelineLimit()
    {
        var fixture = new Fixture();
        var report = await fixture.ReportAsync(TestContext.Current.CancellationToken);
        var command = await fixture.Engine.SubmitAsync(fixture.DeviceId, Request(CameraControlIds.Zoom, new CameraControlValue(Number: 2)), Actor(), TestContext.Current.CancellationToken);

        await fixture.Engine.CompleteAsync(fixture.DeviceId, Fixture.ConnectionId, Result(command, report, false, transient: true), TestContext.Current.CancellationToken);

        var retried = (await fixture.Store.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken))!.Commands.Single(item => item.CommandId == command.CommandId);
        Assert.Equal(CameraControlCommandState.Executing, retried.State);
        Assert.Equal(2, retried.Attempts);
        Assert.Equal(2, fixture.Transport.Dispatched.Count);
    }

    [Fact]
    public async Task TransientSendFailureIsRetriedByDispatcher()
    {
        var fixture = new Fixture();
        await fixture.ReportAsync(TestContext.Current.CancellationToken);
        fixture.Transport.Results.Enqueue(false);
        fixture.Transport.Results.Enqueue(true);

        var command = await fixture.Engine.SubmitAsync(
            fixture.DeviceId,
            Request(CameraControlIds.Torch, new CameraControlValue(Boolean: true)),
            Actor(),
            TestContext.Current.CancellationToken);
        Assert.Equal(CameraControlCommandState.Retrying, command.State);
        Assert.Equal(1, command.Attempts);

        await fixture.Engine.DispatchPendingAsync(TestContext.Current.CancellationToken);

        var retried = (await fixture.Store.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken))!
            .Commands.Single(item => item.CommandId == command.CommandId);
        Assert.Equal(CameraControlCommandState.Executing, retried.State);
        Assert.Equal(2, retried.Attempts);
    }

    [Fact]
    public async Task OfflineCommandWaitsWithReasonThenDispatchesOnReconnect()
    {
        var fixture = new Fixture();
        await fixture.ReportAsync(TestContext.Current.CancellationToken);
        fixture.Connections.Disconnected(Fixture.ConnectionId, Now);

        var queued = await fixture.Engine.SubmitAsync(
            fixture.DeviceId,
            Request(CameraControlIds.Torch, new CameraControlValue(Boolean: true)),
            Actor(),
            TestContext.Current.CancellationToken);
        Assert.Equal(CameraControlCommandState.Queued, queued.State);
        Assert.Equal("waiting_for_device", queued.ResultCode);
        Assert.Equal(0, queued.Attempts);

        const string reconnected = "reconnected-device";
        fixture.Connections.Connected(DeviceId.From(fixture.DeviceId), reconnected, Now);
        await fixture.Engine.DispatchPendingAsync(TestContext.Current.CancellationToken);

        var executing = (await fixture.Store.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken))!
            .Commands.Single(item => item.CommandId == queued.CommandId);
        Assert.Equal(CameraControlCommandState.Executing, executing.State);
        Assert.Equal(1, executing.Attempts);
        Assert.Equal(reconnected, fixture.Transport.ConnectionIds.Single());
    }

    [Fact]
    public async Task LostAcknowledgementRetriesThenReachesTerminalFailure()
    {
        var fixture = new Fixture();
        await fixture.ReportAsync(TestContext.Current.CancellationToken);
        var command = await fixture.Engine.SubmitAsync(
            fixture.DeviceId,
            Request(CameraControlIds.Torch, new CameraControlValue(Boolean: true)),
            Actor(),
            TestContext.Current.CancellationToken);

        fixture.Time.Advance(TimeSpan.FromSeconds(31));
        fixture.Connections.Heartbeat(DeviceId.From(fixture.DeviceId), Fixture.ConnectionId, fixture.Time.GetUtcNow());
        await fixture.Engine.DispatchPendingAsync(TestContext.Current.CancellationToken);
        fixture.Time.Advance(TimeSpan.FromSeconds(31));
        fixture.Connections.Heartbeat(DeviceId.From(fixture.DeviceId), Fixture.ConnectionId, fixture.Time.GetUtcNow());
        await fixture.Engine.DispatchPendingAsync(TestContext.Current.CancellationToken);
        fixture.Time.Advance(TimeSpan.FromSeconds(31));
        fixture.Connections.Heartbeat(DeviceId.From(fixture.DeviceId), Fixture.ConnectionId, fixture.Time.GetUtcNow());
        await fixture.Engine.DispatchPendingAsync(TestContext.Current.CancellationToken);

        var failed = (await fixture.Store.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken))!
            .Commands.Single(item => item.CommandId == command.CommandId);
        Assert.Equal(CameraControlCommandState.Failed, failed.State);
        Assert.Equal(3, failed.Attempts);
        Assert.Equal("connection_interrupted", failed.ResultCode);
        Assert.NotNull(failed.CompletedAtUtc);
    }

    [Fact]
    public async Task OfflineQueueExpiresInsteadOfWaitingIndefinitely()
    {
        var fixture = new Fixture();
        await fixture.ReportAsync(TestContext.Current.CancellationToken);
        fixture.Connections.Disconnected(Fixture.ConnectionId, Now);
        var command = await fixture.Engine.SubmitAsync(
            fixture.DeviceId,
            Request(CameraControlIds.Torch, new CameraControlValue(Boolean: true)),
            Actor(),
            TestContext.Current.CancellationToken);

        fixture.Time.Advance(TimeSpan.FromMinutes(6));
        await fixture.Engine.DispatchPendingAsync(TestContext.Current.CancellationToken);

        var expired = (await fixture.Store.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken))!
            .Commands.Single(item => item.CommandId == command.CommandId);
        Assert.Equal(CameraControlCommandState.Failed, expired.State);
        Assert.Equal("queue_expired", expired.ResultCode);
    }

    [Fact]
    public async Task DuplicateSubmissionReturnsExistingCommandWithoutRedelivery()
    {
        var fixture = new Fixture();
        await fixture.ReportAsync(TestContext.Current.CancellationToken);
        var request = new CameraControlCommandRequest(
            CameraControlIds.Torch,
            new CameraControlValue(Boolean: true),
            "same-request");

        var first = await fixture.Engine.SubmitAsync(
            fixture.DeviceId,
            request,
            Actor(),
            TestContext.Current.CancellationToken);
        var duplicate = await fixture.Engine.SubmitAsync(
            fixture.DeviceId,
            request,
            Actor(),
            TestContext.Current.CancellationToken);

        Assert.Equal(first.CommandId, duplicate.CommandId);
        Assert.Single(fixture.Transport.Dispatched);
        Assert.Single((await fixture.Store.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken))!.Commands);
    }

    [Fact]
    public async Task ExecutingCancellationWaitsForDeviceAcknowledgementBeforeDispatchingNext()
    {
        var fixture = new Fixture();
        var report = await fixture.ReportAsync(TestContext.Current.CancellationToken);
        var first = await fixture.Engine.SubmitAsync(fixture.DeviceId, Request(CameraControlIds.Zoom, new CameraControlValue(Number: 2)), Actor(), TestContext.Current.CancellationToken);
        var second = await fixture.Engine.SubmitAsync(fixture.DeviceId, Request(CameraControlIds.Torch, new CameraControlValue(Boolean: true)), Actor(), TestContext.Current.CancellationToken);

        var canceling = await fixture.Engine.CancelAsync(fixture.DeviceId, first.CommandId, Actor(), TestContext.Current.CancellationToken);

        Assert.Equal(CameraControlCommandState.Executing, canceling.State);
        Assert.False(canceling.Cancelable);
        Assert.Single(fixture.Transport.Canceled);
        Assert.Single(fixture.Transport.Dispatched);

        await fixture.Engine.CompleteAsync(
            fixture.DeviceId,
            Fixture.ConnectionId,
            Result(first, report, false) with { ResultCode = "operator_canceled" },
            TestContext.Current.CancellationToken);

        var stored = await fixture.Store.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken);
        Assert.Equal(CameraControlCommandState.Canceled, stored!.Commands.Single(item => item.CommandId == first.CommandId).State);
        Assert.Equal(second.CommandId, fixture.Transport.Dispatched.Last().CommandId);
    }

    [Fact]
    public async Task SuccessfulCommandUpdatesDesiredSettingsOnlyAfterDeviceCompletion()
    {
        var fixture = new Fixture();
        var report = await fixture.ReportAsync(TestContext.Current.CancellationToken);
        var command = await fixture.Engine.SubmitAsync(fixture.DeviceId, Request(CameraControlIds.Zoom, new CameraControlValue(Number: 2.5)), Actor(), TestContext.Current.CancellationToken);
        Assert.Equal(1, (await fixture.Store.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken))!.DesiredSettings.Zoom);

        await fixture.Engine.CompleteAsync(fixture.DeviceId, Fixture.ConnectionId, Result(command, report with { Settings = report.Settings with { Zoom = 2.5 } }, true), TestContext.Current.CancellationToken);

        Assert.Equal(2.5, (await fixture.Engine.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken)).DesiredSettings.Zoom);
    }

    [Fact]
    public async Task UnsupportedOrOutOfRangeCommandsAreRejectedBeforeDispatch()
    {
        var fixture = new Fixture();
        await fixture.ReportAsync(TestContext.Current.CancellationToken);

        await Assert.ThrowsAsync<DomainValidationException>(() => fixture.Engine.SubmitAsync(
            fixture.DeviceId,
            Request(CameraControlIds.Zoom, new CameraControlValue(Number: 20)),
            Actor(),
            TestContext.Current.CancellationToken));
        await Assert.ThrowsAsync<DomainValidationException>(() => fixture.Engine.SubmitAsync(
            fixture.DeviceId,
            Request("manualFocus", new CameraControlValue(Number: 1)),
            Actor(),
            TestContext.Current.CancellationToken));
        await Assert.ThrowsAsync<DomainValidationException>(() => fixture.Engine.SubmitAsync(
            fixture.DeviceId,
            Request(CameraControlIds.FramesPerSecond, new CameraControlValue(Number: 29.5)),
            Actor(),
            TestContext.Current.CancellationToken));
        Assert.Empty(fixture.Transport.Dispatched);
        var stored = await fixture.Store.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken);
        Assert.Equal(3, stored!.Audit.Count(entry => entry.Action == "rejected"));
        Assert.All(stored.Audit.Where(entry => entry.Action == "rejected"), entry => Assert.Equal("capability_validation_failed", entry.Outcome));
    }

    [Fact]
    public async Task MotionSettingUsesReportedVersionUnifiedDispatchAndAudit()
    {
        var fixture = new Fixture();
        var report = await fixture.ReportAsync(TestContext.Current.CancellationToken);

        var command = await fixture.Engine.SubmitAsync(
            fixture.DeviceId,
            Request(MotionSettingIds.Sensitivity, new CameraControlValue(Text: "high"), expectedVersion: 7),
            Actor(),
            TestContext.Current.CancellationToken);

        var dispatched = Assert.Single(fixture.Transport.Dispatched);
        Assert.Equal(MotionSettingIds.Sensitivity, dispatched.Control);
        Assert.Equal(7, dispatched.ExpectedVersion);
        Assert.Equal("high", dispatched.Value.Text);
        Assert.Equal(CameraControlCommandState.Executing, command.State);
        Assert.Equal(CameraControlSettings.Default, dispatched.DesiredSettings);

        var updatedMotion = report.MotionSettings! with
        {
            Settings = report.MotionSettings!.Settings with { Sensitivity = "high" },
            Version = 8,
            Capabilities = report.MotionSettings.Capabilities.Select(capability =>
                capability.Id == MotionSettingIds.Sensitivity
                    ? capability with { CurrentValue = new MotionSettingValue(Text: "high") }
                    : capability).ToArray(),
        };
        await fixture.Engine.CompleteAsync(
            fixture.DeviceId,
            Fixture.ConnectionId,
            Result(command, report with { MotionSettings = updatedMotion }, succeeded: true) with
            {
                ResultCode = "motion_settings_applied",
            },
            TestContext.Current.CancellationToken);

        var view = await fixture.Engine.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken);
        Assert.Equal("high", view.DeviceState!.MotionSettings!.Settings.Sensitivity);
        Assert.Equal(8, view.DeviceState.MotionSettings.Version);
        Assert.Equal("preset", view.DeviceState.MotionSettings.EffectiveConfiguration?.Source);
        Assert.Equal(CameraControlSettings.Default, view.DesiredSettings);
        var stored = await fixture.Store.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken);
        Assert.Contains(stored!.Audit, entry => entry.CommandId == command.CommandId && entry.Action == "queued");
        Assert.Contains(stored.Audit, entry => entry.CommandId == command.CommandId && entry.Action == "completed");
    }

    [Fact]
    public async Task StaleMotionVersionIsRejectedAndAuditedBeforeDispatch()
    {
        var fixture = new Fixture();
        await fixture.ReportAsync(TestContext.Current.CancellationToken);

        await Assert.ThrowsAsync<ResourceConflictException>(() => fixture.Engine.SubmitAsync(
            fixture.DeviceId,
            Request(MotionSettingIds.Enabled, new CameraControlValue(Boolean: true), expectedVersion: 6),
            Actor(),
            TestContext.Current.CancellationToken));

        Assert.Empty(fixture.Transport.Dispatched);
        var stored = await fixture.Store.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken);
        Assert.Contains(stored!.Audit, entry => entry.Action == "rejected" && entry.ActorId == "operator-1");
    }

    [Fact]
    public async Task LocalMotionReportBecomesHubAuthorityWithoutRestoreCommand()
    {
        var fixture = new Fixture();
        var report = await fixture.ReportAsync(TestContext.Current.CancellationToken);
        var locallyChanged = report.MotionSettings! with
        {
            Settings = report.MotionSettings!.Settings with { CooldownMillis = 10_000 },
            Version = 8,
        };

        await fixture.Engine.ReportAsync(
            fixture.DeviceId,
            Fixture.ConnectionId,
            report with { MotionSettings = locallyChanged },
            TestContext.Current.CancellationToken);

        var view = await fixture.Engine.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken);
        Assert.Equal(10_000, view.DeviceState!.MotionSettings!.Settings.CooldownMillis);
        Assert.Equal(8, view.DeviceState.MotionSettings.Version);
        Assert.DoesNotContain(fixture.Transport.Dispatched, command => MotionSettingIds.IsMotionSetting(command.Control));
    }

    [Fact]
    public async Task ReconnectWithDriftQueuesPersistedRestoreCommand()
    {
        var fixture = new Fixture();
        var report = await fixture.ReportAsync(TestContext.Current.CancellationToken);
        var command = await fixture.Engine.SubmitAsync(fixture.DeviceId, Request(CameraControlIds.Zoom, new CameraControlValue(Number: 2)), Actor(), TestContext.Current.CancellationToken);
        await fixture.Engine.CompleteAsync(fixture.DeviceId, Fixture.ConnectionId, Result(command, report with { Settings = report.Settings with { Zoom = 2 } }, true), TestContext.Current.CancellationToken);

        await fixture.Engine.ReportAsync(fixture.DeviceId, Fixture.ConnectionId, report, TestContext.Current.CancellationToken);

        Assert.Contains((await fixture.Store.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken))!.Commands, item => item.Control == CameraControlIds.Restore);
        Assert.Equal(CameraControlIds.Restore, fixture.Transport.Dispatched.Last().Control);
    }

    [Fact]
    public async Task FailedRestoreIsNotRequeuedForEveryReportOnSameConnection()
    {
        var fixture = new Fixture();
        var report = await fixture.ReportAsync(TestContext.Current.CancellationToken);
        var command = await fixture.Engine.SubmitAsync(
            fixture.DeviceId,
            Request(CameraControlIds.Zoom, new CameraControlValue(Number: 2)),
            Actor(),
            TestContext.Current.CancellationToken);
        await fixture.Engine.CompleteAsync(
            fixture.DeviceId,
            Fixture.ConnectionId,
            Result(command, report with { Settings = report.Settings with { Zoom = 2 } }, true),
            TestContext.Current.CancellationToken);

        await fixture.Engine.ReportAsync(
            fixture.DeviceId,
            Fixture.ConnectionId,
            report,
            TestContext.Current.CancellationToken);
        var restore = (await fixture.Store.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken))!
            .Commands.Single(item => item.Control == CameraControlIds.Restore);
        await fixture.Engine.CompleteAsync(
            fixture.DeviceId,
            Fixture.ConnectionId,
            Result(restore, report, false) with
            {
                ResultCode = "camera_unavailable",
                TransientFailure = false,
            },
            TestContext.Current.CancellationToken);

        await fixture.Engine.ReportAsync(
            fixture.DeviceId,
            Fixture.ConnectionId,
            report,
            TestContext.Current.CancellationToken);

        var stored = await fixture.Store.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken);
        Assert.Single(stored!.Commands, item => item.Control == CameraControlIds.Restore);

        const string reconnected = "replacement-device-connection";
        fixture.Connections.Connected(DeviceId.From(fixture.DeviceId), reconnected, Now);
        await fixture.Engine.ReportAsync(
            fixture.DeviceId,
            reconnected,
            report,
            TestContext.Current.CancellationToken);
        stored = await fixture.Store.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken);
        Assert.Equal(2, stored!.Commands.Count(item => item.Control == CameraControlIds.Restore));
    }

    [Fact]
    public async Task HubRestartRecoversPersistedExecutingCommand()
    {
        var fixture = new Fixture();
        var report = await fixture.ReportAsync(TestContext.Current.CancellationToken);
        var command = await fixture.Engine.SubmitAsync(
            fixture.DeviceId,
            Request(CameraControlIds.Zoom, new CameraControlValue(Number: 2)),
            Actor(),
            TestContext.Current.CancellationToken);
        var restarted = new CameraControlEngine(
            fixture.Store,
            fixture.Transport,
            new NoOpEvents(),
            fixture.Connections,
            new FixedTimeProvider(Now));

        await restarted.ReportAsync(
            fixture.DeviceId,
            Fixture.ConnectionId,
            report,
            TestContext.Current.CancellationToken);

        var recovered = (await fixture.Store.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken))!
            .Commands.Single(item => item.CommandId == command.CommandId);
        Assert.Equal(CameraControlCommandState.Executing, recovered.State);
        Assert.Equal(2, recovered.Attempts);
        Assert.Equal(2, fixture.Transport.Dispatched.Count);
        Assert.Contains(
            (await fixture.Store.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken))!.Audit,
            entry => entry.CommandId == command.CommandId && entry.Action == "recovered");
    }

    [Fact]
    public async Task DeviceReconnectRecoversOnlyCommandOwnedByPreviousConnection()
    {
        var fixture = new Fixture();
        var report = await fixture.ReportAsync(TestContext.Current.CancellationToken);
        var command = await fixture.Engine.SubmitAsync(
            fixture.DeviceId,
            Request(CameraControlIds.Zoom, new CameraControlValue(Number: 2)),
            Actor(),
            TestContext.Current.CancellationToken);

        await fixture.Engine.ReportAsync(
            fixture.DeviceId,
            Fixture.ConnectionId,
            report,
            TestContext.Current.CancellationToken);
        Assert.Single(fixture.Transport.Dispatched);

        const string reconnected = "replacement-device-connection";
        fixture.Connections.Connected(DeviceId.From(fixture.DeviceId), reconnected, Now);
        await fixture.Engine.ReportAsync(
            fixture.DeviceId,
            reconnected,
            report,
            TestContext.Current.CancellationToken);

        var recovered = (await fixture.Store.GetAsync(fixture.DeviceId, TestContext.Current.CancellationToken))!
            .Commands.Single(item => item.CommandId == command.CommandId);
        Assert.Equal(CameraControlCommandState.Executing, recovered.State);
        Assert.Equal(2, recovered.Attempts);
        Assert.Equal(2, fixture.Transport.Dispatched.Count);
    }

    [Fact]
    public async Task FileStoreSurvivesHubRestartWithoutLeavingConfiguredDataDirectory()
    {
        var root = Path.Combine(Path.GetTempPath(), "sentricam-camera-control-" + Guid.NewGuid().ToString("N"));
        try
        {
            var deviceId = Guid.NewGuid();
            var overlay = new DateTimeOverlayConfiguration(true, true, false, true, DateTimeOverlayPositions.BottomRight);
            var stored = new StoredCameraControlDevice(deviceId, CameraControlSettings.Default with { Zoom = 3, DateTimeOverlay = overlay }, null, [], [], Now);
            using (var first = new FileCameraControlStore(root)) await first.SaveAsync(stored, TestContext.Current.CancellationToken);
            using var restarted = new FileCameraControlStore(root);

            Assert.Equal(3, (await restarted.GetAsync(deviceId, TestContext.Current.CancellationToken))!.DesiredSettings.Zoom);
            Assert.Equal(overlay, (await restarted.GetAsync(deviceId, TestContext.Current.CancellationToken))!.DesiredSettings.DateTimeOverlay);
            Assert.True(File.Exists(Path.Combine(root, "camera-control", "state.json")));
        }
        finally
        {
            if (Directory.Exists(root)) Directory.Delete(root, recursive: true);
        }
    }

    private static CameraControlCommandRequest Request(string control, CameraControlValue value, long? expectedVersion = null) =>
        new(control, value, Guid.NewGuid().ToString("N"), expectedVersion);

    private static CameraControlActor Actor() => new("operator-1");

    private static CameraControlCommandResult Result(
        CameraControlCommandView command,
        CameraControlDeviceReport report,
        bool succeeded,
        bool transient = false) =>
        new(command.CommandId, command.DeviceId, succeeded, transient, succeeded ? "applied" : "camera_busy", report, Now);

    private sealed class Fixture
    {
        public const string ConnectionId = "device-connection";
        public Guid DeviceId { get; } = Guid.NewGuid();
        public MemoryStore Store { get; } = new();
        public CaptureTransport Transport { get; } = new();
        public MutableTimeProvider Time { get; } = new(Now);
        public DeviceConnectionManager Connections { get; }
        public CameraControlEngine Engine { get; }

        public Fixture()
        {
            Connections = new DeviceConnectionManager(Time);
            Connections.Connected(SentriCam.Domain.Devices.DeviceId.From(DeviceId), ConnectionId, Now);
            Engine = new CameraControlEngine(Store, Transport, new NoOpEvents(), Connections, Time);
        }

        public async Task<CameraControlDeviceReport> ReportAsync(CancellationToken cancellationToken)
        {
            var report = new CameraControlDeviceReport(
                DeviceId,
                CameraControlSettings.Default,
                [
                    Capability(CameraControlIds.Zoom, true, true, 1, 1, 4),
                    Capability(CameraControlIds.Torch, true, true, boolean: false),
                    Capability(CameraControlIds.FramesPerSecond, true, true, 30, 15, 60),
                    Capability(CameraControlIds.Recording, true, true, text: "idle", allowed: ["start", "stop"]),
                    new CameraCapabilityDescriptor(
                        CameraControlIds.DateTimeOverlay,
                        true,
                        true,
                        new CameraControlValue(DateTimeOverlay: CameraControlSettings.Default.DateTimeOverlay)),
                    Capability("manualFocus", true, false, 0, 0, 10),
                ],
                new CameraControlTelemetry(true, false, false, true, 80, 31, 10_000, false, "good", true, true),
                Now,
                MotionReport());
            await Engine.ReportAsync(DeviceId, ConnectionId, report, cancellationToken);
            return report;
        }

        private static CameraCapabilityDescriptor Capability(
            string id,
            bool supported,
            bool writable,
            double? number = null,
            double? minimum = null,
            double? maximum = null,
            bool? boolean = null,
            string? text = null,
            IReadOnlyList<string>? allowed = null) =>
            new(id, supported, writable, new CameraControlValue(boolean, number, text), minimum, maximum, AllowedValues: allowed);

        private static MotionSettingsDeviceReport MotionReport() => new(
            new MotionSettingsValues(false, "medium", 50, 1_000, 10_000, 5_000),
            [
                MotionCapability(MotionSettingIds.Enabled, boolean: false),
                MotionCapability(MotionSettingIds.Sensitivity, text: "medium", allowed: ["low", "medium", "high", "advanced"]),
                MotionCapability(MotionSettingIds.AdvancedSensitivity, number: 50, minimum: 0, maximum: 100),
                MotionCapability(MotionSettingIds.TriggerDelay, number: 1_000, allowed: ["0", "1000", "2000"]),
                MotionCapability(MotionSettingIds.StopDelay, number: 10_000, allowed: ["5000", "10000", "20000", "30000"]),
                MotionCapability(MotionSettingIds.Cooldown, number: 5_000, allowed: ["3000", "5000", "10000"]),
                MotionCapability(MotionSettingIds.FrameInterval, writable: false, number: 150),
                MotionCapability(MotionSettingIds.WarmupFrames, writable: false, number: 6),
            ],
            7,
            new MotionEffectiveConfiguration("medium", "preset", 0.075, 3, 0.014, 0.050, 0.220, "balanced"));

        private static MotionSettingDescriptor MotionCapability(
            string id,
            bool writable = true,
            double? number = null,
            double? minimum = null,
            double? maximum = null,
            bool? boolean = null,
            string? text = null,
            IReadOnlyList<string>? allowed = null) =>
            new(id, true, writable, new MotionSettingValue(boolean, number, text), minimum, maximum, AllowedValues: allowed);
    }

    private sealed class MemoryStore : ICameraControlStore
    {
        private readonly Dictionary<Guid, StoredCameraControlDevice> _items = [];
        public Task<StoredCameraControlDevice?> GetAsync(Guid deviceId, CancellationToken cancellationToken = default) => Task.FromResult(_items.GetValueOrDefault(deviceId));
        public Task<IReadOnlyList<StoredCameraControlDevice>> GetAllAsync(CancellationToken cancellationToken = default) =>
            Task.FromResult((IReadOnlyList<StoredCameraControlDevice>)_items.Values.ToArray());
        public Task SaveAsync(StoredCameraControlDevice device, CancellationToken cancellationToken = default) { _items[device.DeviceId] = device; return Task.CompletedTask; }
    }

    private sealed class CaptureTransport : ICameraControlTransport
    {
        public List<CameraControlCommandEnvelope> Dispatched { get; } = [];
        public List<CameraControlCancellation> Canceled { get; } = [];
        public List<string> ConnectionIds { get; } = [];
        public Queue<bool> Results { get; } = [];
        public Task<bool> DispatchAsync(string deviceConnectionId, CameraControlCommandEnvelope command, CancellationToken cancellationToken = default)
        {
            ConnectionIds.Add(deviceConnectionId);
            Dispatched.Add(command);
            return Task.FromResult(Results.TryDequeue(out var result) ? result : true);
        }
        public Task CancelAsync(string deviceConnectionId, CameraControlCancellation cancellation, CancellationToken cancellationToken = default) { Canceled.Add(cancellation); return Task.CompletedTask; }
    }

    private sealed class NoOpEvents : ICameraControlEventPublisher
    {
        public Task ChangedAsync(CameraControlUpdate update, CancellationToken cancellationToken = default) => Task.CompletedTask;
    }

    private sealed class FixedTimeProvider(DateTimeOffset value) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => value;
    }

    private sealed class MutableTimeProvider(DateTimeOffset value) : TimeProvider
    {
        private DateTimeOffset _value = value;
        public override DateTimeOffset GetUtcNow() => _value;
        public void Advance(TimeSpan amount) => _value += amount;
    }
}
