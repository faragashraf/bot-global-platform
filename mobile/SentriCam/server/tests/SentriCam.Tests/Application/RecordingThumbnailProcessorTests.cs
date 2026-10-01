using SentriCam.Application.Recordings;
using SentriCam.Domain.Recordings;
using SentriCam.Tests.TestDoubles;

namespace SentriCam.Tests.Application;

public sealed class RecordingThumbnailProcessorTests
{
    [Fact]
    public async Task ProcessingCreatesRealJpegAndPersistsReadyState()
    {
        var fixture = CreateFixture(new GeneratedRecordingThumbnail([0xff, 0xd8, 0xff, 0xd9], ".jpg", "image/jpeg"));

        await fixture.Processor.ProcessAsync(fixture.Recording.Id, TestContext.Current.CancellationToken);

        Assert.Equal(ThumbnailGenerationState.Ready, fixture.Recording.ThumbnailState);
        Assert.Equal("thumbnail.jpg", fixture.Recording.ThumbnailRelativePath);
        Assert.Equal(1, fixture.Recording.ThumbnailGenerationAttempts);
        Assert.NotNull(fixture.Recording.ThumbnailGeneratedUtc);
    }

    [Fact]
    public async Task FailureUsesFallbackStateAndExplicitRetryCanSucceed()
    {
        var fixture = CreateFixture(null, "ffmpeg_unavailable");
        await fixture.Processor.ProcessAsync(fixture.Recording.Id, TestContext.Current.CancellationToken);
        Assert.Equal(ThumbnailGenerationState.Failed, fixture.Recording.ThumbnailState);
        Assert.Equal("ffmpeg_unavailable", fixture.Recording.ThumbnailErrorCode);

        fixture.Recording.RequestThumbnailRegeneration();
        fixture.Generator.Result = new GeneratedRecordingThumbnail([0xff, 0xd8, 0xff, 0xd9], ".jpg", "image/jpeg");
        fixture.Generator.ErrorCode = null;
        await fixture.Processor.ProcessAsync(fixture.Recording.Id, TestContext.Current.CancellationToken);

        Assert.Equal(ThumbnailGenerationState.Ready, fixture.Recording.ThumbnailState);
        Assert.Equal(2, fixture.Recording.ThumbnailGenerationAttempts);
    }

    [Fact]
    public async Task ProcessingIsIdempotentAfterReady()
    {
        var fixture = CreateFixture(new GeneratedRecordingThumbnail([0xff, 0xd8, 0xff, 0xd9], ".jpg", "image/jpeg"));
        await fixture.Processor.ProcessAsync(fixture.Recording.Id, TestContext.Current.CancellationToken);
        await fixture.Processor.ProcessAsync(fixture.Recording.Id, TestContext.Current.CancellationToken);
        Assert.Equal(1, fixture.Generator.Calls);
    }

    private static Fixture CreateFixture(GeneratedRecordingThumbnail? result, string? errorCode = null)
    {
        var device = DeviceTestFactory.CreateDevice();
        var recording = Recording.Create(
            Guid.NewGuid(), device.Id, "client", "session", "capture.mp4", "video/mp4", 10_000, 4,
            DeviceTestFactory.Now.AddMinutes(-1), DeviceTestFactory.Now, true, false, new string('a', 64), "recording.mp4");
        var recordings = new FakeRecordingRepository();
        recordings.Add(recording);
        var storage = new ThumbnailStorage();
        var generator = new ConfigurableGenerator { Result = result, ErrorCode = errorCode };
        var unit = new FakeUnitOfWork();
        var processor = new RecordingThumbnailProcessor(
            recordings,
            storage,
            generator,
            unit,
            new FixedTimeProvider(DeviceTestFactory.Now));
        return new Fixture(recording, generator, processor);
    }

    private sealed class ConfigurableGenerator : IRecordingThumbnailGenerator
    {
        public GeneratedRecordingThumbnail? Result { get; set; }
        public string? ErrorCode { get; set; }
        public int Calls { get; private set; }
        public Task<GeneratedRecordingThumbnail> GenerateAsync(RecordingThumbnailRequest request, CancellationToken cancellationToken = default)
        {
            Calls++;
            if (ErrorCode is not null) throw new RecordingThumbnailGenerationException(ErrorCode, "Simulated media failure.");
            return Task.FromResult(Result!);
        }
    }

    private sealed class ThumbnailStorage : IRecordingStorageProvider
    {
        public Task<StoredRecordingFile> SaveRecordingAsync(RecordingStorageWriteRequest request, Stream content, CancellationToken cancellationToken = default) => throw new NotSupportedException();
        public Task<string> SaveThumbnailAsync(RecordingStorageWriteRequest request, GeneratedRecordingThumbnail thumbnail, CancellationToken cancellationToken = default) => Task.FromResult("thumbnail.jpg");
        public Task<Stream> OpenReadAsync(string relativePath, CancellationToken cancellationToken = default) => throw new NotSupportedException();
        public Task DeleteIfExistsAsync(string relativePath, CancellationToken cancellationToken = default) => Task.CompletedTask;
    }

    private sealed record Fixture(Recording Recording, ConfigurableGenerator Generator, RecordingThumbnailProcessor Processor);
}
