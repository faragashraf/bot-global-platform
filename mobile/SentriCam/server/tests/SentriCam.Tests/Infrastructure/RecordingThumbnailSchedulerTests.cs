using SentriCam.Infrastructure.Storage;

namespace SentriCam.Tests.Infrastructure;

public sealed class RecordingThumbnailSchedulerTests
{
    [Fact]
    public async Task DuplicateThumbnailJobsArePreventedUntilCompletion()
    {
        var scheduler = new RecordingThumbnailScheduler();
        var id = Guid.NewGuid();
        Assert.True(scheduler.Enqueue(id));
        Assert.False(scheduler.Enqueue(id));

        await foreach (var queued in scheduler.ReadAllAsync(TestContext.Current.CancellationToken))
        {
            Assert.Equal(id, queued);
            scheduler.Complete(id);
            break;
        }
        Assert.True(scheduler.Enqueue(id));
    }
}
