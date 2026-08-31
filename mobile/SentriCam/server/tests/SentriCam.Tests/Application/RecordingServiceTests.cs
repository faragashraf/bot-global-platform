using System.Security.Cryptography;
using SentriCam.Application.Common;
using SentriCam.Application.Recordings;
using SentriCam.Contracts.Recordings;
using SentriCam.Domain.Recordings;
using SentriCam.Tests.TestDoubles;

namespace SentriCam.Tests.Application;

public sealed class RecordingServiceTests
{
    [Fact]
    public async Task UploadPersistsVerifiedMetadataAndSchedulesThumbnailWithoutBlocking()
    {
        var fixture = CreateFixture();
        var content = new byte[] { 1, 2, 3, 4 };

        var result = await fixture.Service.UploadAsync(
            CreateUpload(fixture.Device.Id.Value, content),
            TestContext.Current.CancellationToken);

        Assert.False(result.Duplicate);
        var recording = Assert.Single(fixture.Recordings.Recordings);
        Assert.Equal(content.Length, recording.SizeBytes);
        Assert.Equal(Sha256(content), recording.ChecksumSha256);
        Assert.Null(recording.ThumbnailRelativePath);
        Assert.Equal(ThumbnailGenerationState.Pending, recording.ThumbnailState);
        Assert.Contains(recording.Id, fixture.Thumbnails.Scheduled);
        Assert.Equal(1, fixture.Storage.SaveCount);
        Assert.Equal(1, fixture.UnitOfWork.SaveCount);
        var library = await fixture.Service.ListAsync(
            new RecordingQuery(),
            TestContext.Current.CancellationToken);
        Assert.Equal(result.Recording.RecordingId, Assert.Single(library.Items).RecordingId);
    }

    [Fact]
    public async Task DuplicateClientRecordingReturnsExistingWithoutWritingContentAgain()
    {
        var fixture = CreateFixture();
        var content = new byte[] { 1, 2, 3, 4 };
        var upload = CreateUpload(fixture.Device.Id.Value, content);
        await fixture.Service.UploadAsync(upload, TestContext.Current.CancellationToken);

        var duplicate = await fixture.Service.UploadAsync(
            upload with { Content = new MemoryStream(content) },
            TestContext.Current.CancellationToken);

        Assert.True(duplicate.Duplicate);
        Assert.Single(fixture.Recordings.Recordings);
        Assert.Equal(1, fixture.Storage.SaveCount);
    }

    [Fact]
    public async Task DuplicateClientRecordingRejectsDifferentChecksum()
    {
        var fixture = CreateFixture();
        var content = new byte[] { 1, 2, 3, 4 };
        var upload = CreateUpload(fixture.Device.Id.Value, content);
        await fixture.Service.UploadAsync(upload, TestContext.Current.CancellationToken);

        await Assert.ThrowsAsync<ResourceConflictException>(() => fixture.Service.UploadAsync(
            upload with
            {
                ChecksumSha256 = new string('b', 64),
                Content = new MemoryStream(content),
            },
            TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task ChecksumMismatchRemovesUncommittedStorageObject()
    {
        var fixture = CreateFixture(storedChecksum: new string('f', 64));
        var content = new byte[] { 1, 2, 3, 4 };

        await Assert.ThrowsAsync<ResourceConflictException>(() => fixture.Service.UploadAsync(
            CreateUpload(fixture.Device.Id.Value, content),
            TestContext.Current.CancellationToken));

        Assert.Empty(fixture.Recordings.Recordings);
        Assert.Contains("recording.mp4", fixture.Storage.Deleted);
    }

    [Fact]
    public async Task UploadRejectsNonMp4ContentBeforeWritingStorage()
    {
        var fixture = CreateFixture();
        var content = new byte[] { 1, 2, 3, 4 };

        await Assert.ThrowsAsync<SentriCam.Domain.Common.DomainValidationException>(() =>
            fixture.Service.UploadAsync(
                CreateUpload(fixture.Device.Id.Value, content) with
                {
                    OriginalFileName = "capture.html",
                    ContentType = "text/html",
                },
                TestContext.Current.CancellationToken));

        Assert.Equal(0, fixture.Storage.SaveCount);
    }

    [Fact]
    public async Task ListFiltersByTriggerAndReturnsNewestFirst()
    {
        var fixture = CreateFixture();
        fixture.Recordings.Recordings.Add(CreateRecording(fixture, "motion", true, DeviceTestFactory.Now));
        fixture.Recordings.Recordings.Add(CreateRecording(fixture, "manual", false, DeviceTestFactory.Now.AddMinutes(1)));

        var result = await fixture.Service.ListAsync(
            new RecordingQuery(Source: RecordingSourceFilter.Manual),
            TestContext.Current.CancellationToken);

        Assert.Equal("manual", Assert.Single(result.Items).ClientRecordingId);
    }

    [Fact]
    public async Task ExactDayAndRangeFiltersAreNormalizedBeforeRepositoryQuery()
    {
        var fixture = CreateFixture();
        fixture.Recordings.Recordings.Add(CreateRecording(fixture, "selected", true, new DateTimeOffset(2026, 8, 1, 23, 30, 0, TimeSpan.Zero)));
        fixture.Recordings.Recordings.Add(CreateRecording(fixture, "excluded", true, new DateTimeOffset(2026, 8, 2, 0, 0, 0, TimeSpan.Zero)));

        var result = await fixture.Service.ListAsync(
            new RecordingQuery(ExactDayUtc: new DateOnly(2026, 8, 1)),
            TestContext.Current.CancellationToken);

        Assert.Equal("selected", Assert.Single(result.Items).ClientRecordingId);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(101)]
    public async Task MaximumPageSizeIsEnforced(int pageSize)
    {
        var fixture = CreateFixture();
        await Assert.ThrowsAsync<SentriCam.Domain.Common.DomainValidationException>(() => fixture.Service.ListAsync(
            new RecordingQuery(PageSize: pageSize),
            TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task InvalidSortIsRejected()
    {
        var fixture = CreateFixture();
        await Assert.ThrowsAsync<SentriCam.Domain.Common.DomainValidationException>(() => fixture.Service.ListAsync(
            new RecordingQuery(Sort: (RecordingSort)999),
            TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task ExistingFailedRecordingCanBeScheduledForRegeneration()
    {
        var fixture = CreateFixture();
        var recording = CreateRecording(fixture, "retry", true, DeviceTestFactory.Now);
        Assert.True(recording.TryBeginThumbnailGeneration(DeviceTestFactory.Now));
        recording.FailThumbnailGeneration("ffmpeg_failed");
        fixture.Recordings.Add(recording);

        await fixture.Service.RequestThumbnailRegenerationAsync(recording.Id, TestContext.Current.CancellationToken);

        Assert.Equal(ThumbnailGenerationState.Pending, recording.ThumbnailState);
        Assert.Contains(recording.Id, fixture.Thumbnails.Scheduled);
    }

    [Fact]
    public async Task TimeAggregationBuildsLazyYearMonthWeekDayHourHierarchyWithUtcBoundaries()
    {
        var fixture = CreateFixture();
        fixture.Recordings.Recordings.Add(CreateRecording(
            fixture,
            "august",
            true,
            new DateTimeOffset(2026, 8, 1, 9, 30, 0, TimeSpan.Zero)));

        var years = await fixture.Service.AggregateTimeAsync(
            new RecordingTimeQuery(RecordingTimeLevel.Year),
            TestContext.Current.CancellationToken);
        var year = Assert.Single(years);
        Assert.Equal(RecordingTimeLevel.Month, year.ChildrenLevel);
        Assert.Equal(new DateTimeOffset(2026, 1, 1, 0, 0, 0, TimeSpan.Zero), year.StartUtc);

        var months = await fixture.Service.AggregateTimeAsync(
            new RecordingTimeQuery(RecordingTimeLevel.Month, year.StartUtc, year.EndUtc),
            TestContext.Current.CancellationToken);
        var month = Assert.Single(months);
        Assert.Equal(RecordingTimeLevel.Week, month.ChildrenLevel);

        var weeks = await fixture.Service.AggregateTimeAsync(
            new RecordingTimeQuery(RecordingTimeLevel.Week, month.StartUtc, month.EndUtc),
            TestContext.Current.CancellationToken);
        var week = Assert.Single(weeks);
        Assert.Equal(month.StartUtc, week.StartUtc);
        Assert.Equal(RecordingTimeLevel.Day, week.ChildrenLevel);

        var days = await fixture.Service.AggregateTimeAsync(
            new RecordingTimeQuery(RecordingTimeLevel.Day, week.StartUtc, week.EndUtc),
            TestContext.Current.CancellationToken);
        var day = Assert.Single(days);
        Assert.Equal(RecordingTimeLevel.Hour, day.ChildrenLevel);

        var hours = await fixture.Service.AggregateTimeAsync(
            new RecordingTimeQuery(RecordingTimeLevel.Hour, day.StartUtc, day.EndUtc),
            TestContext.Current.CancellationToken);
        var hour = Assert.Single(hours);
        Assert.False(hour.HasChildren);
        Assert.Equal(new DateTimeOffset(2026, 8, 1, 9, 0, 0, TimeSpan.Zero), hour.StartUtc);
        Assert.Equal(hour.StartUtc.AddHours(1), hour.EndUtc);
    }

    [Fact]
    public async Task DeleteDoesNotRemoveStoredObjectsBeforeMetadataCommitSucceeds()
    {
        var fixture = CreateFixture(failSave: true);
        var recording = CreateRecording(fixture, "delete-me", false, DeviceTestFactory.Now);
        Assert.True(recording.TryBeginThumbnailGeneration(DeviceTestFactory.Now));
        recording.CompleteThumbnailGeneration("delete-me.jpg", DeviceTestFactory.Now);
        fixture.Recordings.Add(recording);

        await Assert.ThrowsAsync<InvalidOperationException>(() => fixture.Service.DeleteAsync(
            recording.Id,
            TestContext.Current.CancellationToken));

        Assert.Empty(fixture.Storage.Deleted);
    }

    private static Fixture CreateFixture(string? storedChecksum = null, bool failSave = false)
    {
        var device = DeviceTestFactory.CreateDevice();
        var devices = new FakeDeviceRepository();
        devices.Add(device);
        var recordings = new FakeRecordingRepository();
        var storage = new FakeRecordingStorage(storedChecksum);
        var thumbnails = new FakeThumbnailScheduler();
        var unitOfWork = new FakeUnitOfWork(failSave);
        var service = new RecordingService(
            recordings,
            devices,
            storage,
            thumbnails,
            new FakeDeviceRequestAuthorizer(device.Id),
            unitOfWork,
            new FixedTimeProvider(DeviceTestFactory.Now));
        return new Fixture(device, recordings, storage, thumbnails, unitOfWork, service);
    }

    private static UploadRecording CreateUpload(Guid deviceId, byte[] content) => new(
        deviceId,
        "segment-1",
        "session-1",
        "capture.mp4",
        "video/mp4",
        2_000,
        content.Length,
        DeviceTestFactory.Now.AddMinutes(-1),
        Motion: true,
        Manual: false,
        Sha256(content),
        new MemoryStream(content));

    private static Recording CreateRecording(Fixture fixture, string clientId, bool motion, DateTimeOffset created) =>
        Recording.Create(
            Guid.NewGuid(),
            fixture.Device.Id,
            clientId,
            "session",
            $"{clientId}.mp4",
            "video/mp4",
            1_000,
            10,
            created,
            created.AddMinutes(1),
            motion,
            !motion,
            new string('a', 64),
            $"{clientId}.mp4");

    private static string Sha256(byte[] content) =>
        Convert.ToHexString(SHA256.HashData(content)).ToLowerInvariant();

    private sealed class FakeRecordingStorage(string? checksum = null) : IRecordingStorageProvider
    {
        public int SaveCount { get; private set; }
        public List<string> Deleted { get; } = [];

        public async Task<StoredRecordingFile> SaveRecordingAsync(
            RecordingStorageWriteRequest request,
            Stream content,
            CancellationToken cancellationToken = default)
        {
            SaveCount++;
            using var memory = new MemoryStream();
            await content.CopyToAsync(memory, cancellationToken);
            return new StoredRecordingFile(
                "recording.mp4",
                memory.Length,
                checksum ?? Sha256(memory.ToArray()));
        }

        public Task<string> SaveThumbnailAsync(
            RecordingStorageWriteRequest request,
            GeneratedRecordingThumbnail thumbnail,
            CancellationToken cancellationToken = default) => Task.FromResult("recording.svg");

        public Task<Stream> OpenReadAsync(string relativePath, CancellationToken cancellationToken = default) =>
            Task.FromResult<Stream>(new MemoryStream([1]));

        public Task DeleteIfExistsAsync(string relativePath, CancellationToken cancellationToken = default)
        {
            Deleted.Add(relativePath);
            return Task.CompletedTask;
        }
    }

    private sealed class FakeThumbnailScheduler : IRecordingThumbnailScheduler
    {
        public List<Guid> Scheduled { get; } = [];
        public bool Enqueue(Guid recordingId)
        {
            if (Scheduled.Contains(recordingId)) return false;
            Scheduled.Add(recordingId);
            return true;
        }
        public async IAsyncEnumerable<Guid> ReadAllAsync(
            [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken cancellationToken = default)
        {
            await Task.CompletedTask;
            yield break;
        }
        public void Complete(Guid recordingId) => Scheduled.Remove(recordingId);
    }

    private sealed record Fixture(
        SentriCam.Domain.Devices.Device Device,
        FakeRecordingRepository Recordings,
        FakeRecordingStorage Storage,
        FakeThumbnailScheduler Thumbnails,
        FakeUnitOfWork UnitOfWork,
        RecordingService Service);
}
