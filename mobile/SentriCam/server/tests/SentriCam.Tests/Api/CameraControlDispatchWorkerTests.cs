using Microsoft.Extensions.Logging.Abstractions;
using SentriCam.Api.CameraControl;
using SentriCam.Application.CameraControl;

namespace SentriCam.Tests.Api;

public sealed class CameraControlDispatchWorkerTests
{
    [Fact]
    public async Task WorkerStartsAndPollsDispatcherImmediately()
    {
        var dispatcher = new CaptureDispatcher();
        var worker = new CameraControlDispatchWorker(
            dispatcher,
            NullLogger<CameraControlDispatchWorker>.Instance);
        using var cancellation = new CancellationTokenSource(TimeSpan.FromSeconds(2));

        await worker.StartAsync(cancellation.Token);
        await dispatcher.Polled.Task.WaitAsync(cancellation.Token);
        await worker.StopAsync(CancellationToken.None);

        Assert.True(dispatcher.PollCount >= 1);
    }

    private sealed class CaptureDispatcher : ICameraControlDispatcher
    {
        public TaskCompletionSource Polled { get; } = new(
            TaskCreationOptions.RunContinuationsAsynchronously);
        public int PollCount { get; private set; }

        public Task DispatchPendingAsync(CancellationToken cancellationToken = default)
        {
            PollCount++;
            Polled.TrySetResult();
            return Task.CompletedTask;
        }
    }
}
