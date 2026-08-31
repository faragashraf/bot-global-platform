using SentriCam.Application.CameraControl;
using SentriCam.Application.Common;
using SentriCam.Contracts.CameraControl;

namespace SentriCam.Tests.Application;

public sealed class CameraControlGroupEngineTests
{
    [Fact]
    public async Task PartialFailurePreservesSuccessfulAuditedDeviceCommands()
    {
        var first = Guid.NewGuid();
        var second = Guid.NewGuid();
        var commands = new FakeCommands(second);
        var engine = new CameraControlGroupEngine(commands);

        var result = await engine.SubmitAsync(
            new([first, second], CameraControlIds.Recording, new(Text: CameraControlValues.Start), "wall-start"),
            new CameraControlActor("operator-1"),
            TestContext.Current.CancellationToken);

        Assert.Equal(1, result.Succeeded);
        Assert.Equal(1, result.Failed);
        Assert.True(result.Items.Single(item => item.DeviceId == first).Succeeded);
        Assert.Equal("resource_conflict", result.Items.Single(item => item.DeviceId == second).ErrorCode);
        Assert.Equal(2, commands.Submissions.Count);
        Assert.All(commands.Submissions, submission => Assert.StartsWith("wall-start:", submission.Request.CorrelationId));
    }

    [Fact]
    public async Task DuplicateDeviceIdsExecuteOnlyOnce()
    {
        var deviceId = Guid.NewGuid();
        var commands = new FakeCommands();
        var engine = new CameraControlGroupEngine(commands);

        var result = await engine.SubmitAsync(
            new([deviceId, deviceId], CameraControlIds.Recording, new(Text: CameraControlValues.Stop)),
            new CameraControlActor("operator-1"),
            TestContext.Current.CancellationToken);

        Assert.Single(result.Items);
        Assert.Single(commands.Submissions);
    }

    private sealed class FakeCommands(Guid? failingDeviceId = null) : ICameraControlEngine
    {
        public List<(Guid DeviceId, CameraControlCommandRequest Request)> Submissions { get; } = [];

        public Task<CameraControlCommandView> SubmitAsync(Guid deviceId, CameraControlCommandRequest request, CameraControlActor actor, CancellationToken cancellationToken = default)
        {
            Submissions.Add((deviceId, request));
            if (deviceId == failingDeviceId) throw new ResourceConflictException("Another recording owner is active.");
            var now = DateTimeOffset.UtcNow;
            return Task.FromResult(new CameraControlCommandView(
                Guid.NewGuid(), deviceId, request.Control, request.Value,
                CameraControlCommandState.Queued, 0, true, true,
                request.CorrelationId ?? string.Empty, actor.Subject, null, now, null));
        }

        public Task<CameraControlCenterView> GetAsync(Guid deviceId, CancellationToken cancellationToken = default) => throw new NotSupportedException();
        public Task<CameraControlCommandView> CancelAsync(Guid deviceId, Guid commandId, CameraControlActor actor, CancellationToken cancellationToken = default) => throw new NotSupportedException();
        public Task<int> ResolveForDeviceRemovalAsync(Guid deviceId, CameraControlActor actor, CancellationToken cancellationToken = default) => throw new NotSupportedException();
        public Task ReportAsync(Guid authenticatedDeviceId, string connectionId, CameraControlDeviceReport report, CancellationToken cancellationToken = default) => throw new NotSupportedException();
        public Task CompleteAsync(Guid authenticatedDeviceId, string connectionId, CameraControlCommandResult result, CancellationToken cancellationToken = default) => throw new NotSupportedException();
    }
}
