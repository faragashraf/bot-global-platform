using Microsoft.EntityFrameworkCore;
using Microsoft.Data.Sqlite;
using System.Globalization;
using SentriCam.Application.Recordings;
using SentriCam.Contracts.Recordings;
using SentriCam.Domain.Devices;
using SentriCam.Domain.Recordings;
using SentriCam.Infrastructure.Persistence;
using SentriCam.Infrastructure.Persistence.Repositories;
using SentriCam.Tests.TestDoubles;

namespace SentriCam.Tests.Infrastructure;

public sealed class RecordingRepositoryQueryTests
{
    [Fact]
    public async Task QueryAppliesDeviceDateHourSourceDurationSizeThumbnailAndSearchFiltersServerSide()
    {
        await using var context = CreateContext();
        var front = CreateDevice("front", "Front Entrance");
        var rear = CreateDevice("rear", "Rear Yard");
        context.Devices.AddRange(front, rear);
        var match = CreateRecording(rear, "manual-match", new DateTimeOffset(2026, 8, 1, 9, 15, 0, TimeSpan.Zero), false, 90_000, 5_000);
        match.RequestThumbnailRegeneration();
        context.Recordings.AddRange(
            CreateRecording(front, "motion-front", new DateTimeOffset(2026, 8, 1, 9, 10, 0, TimeSpan.Zero), true, 10_000, 500),
            match,
            CreateRecording(rear, "manual-late", new DateTimeOffset(2026, 8, 1, 11, 0, 0, TimeSpan.Zero), false, 120_000, 9_000));
        SetRowVersions(context);
        await context.SaveChangesAsync(TestContext.Current.CancellationToken);
        var repository = new RecordingRepository(context);

        var result = await repository.QueryAsync(
            new RecordingQuery(
                DeviceIds: [rear.Id.Value],
                Search: "Rear",
                Source: RecordingSourceFilter.Manual,
                DateFromUtc: new DateTimeOffset(2026, 8, 1, 0, 0, 0, TimeSpan.Zero),
                DateToUtc: new DateTimeOffset(2026, 8, 2, 0, 0, 0, TimeSpan.Zero),
                HourFrom: 9,
                HourTo: 10,
                MinimumDurationMilliseconds: 80_000,
                MaximumDurationMilliseconds: 100_000,
                MinimumSizeBytes: 4_000,
                MaximumSizeBytes: 6_000,
                ThumbnailState: RecordingThumbnailState.Pending),
            TestContext.Current.CancellationToken);

        Assert.Equal("manual-match", Assert.Single(result.Items).ClientRecordingId);
        Assert.Equal(1, result.TotalCount);
    }

    [Theory]
    [InlineData(RecordingSort.Newest, "long-large")]
    [InlineData(RecordingSort.Oldest, "short-small")]
    [InlineData(RecordingSort.Longest, "long-large")]
    [InlineData(RecordingSort.Shortest, "short-small")]
    [InlineData(RecordingSort.Largest, "long-large")]
    [InlineData(RecordingSort.Smallest, "short-small")]
    [InlineData(RecordingSort.UploadTime, "long-large")]
    public async Task QueryAppliesAllowListedSorts(RecordingSort sort, string expectedFirst)
    {
        await using var context = CreateContext();
        var device = CreateDevice("sort", "Alpha Camera");
        context.Devices.Add(device);
        context.Recordings.AddRange(
            CreateRecording(device, "short-small", new DateTimeOffset(2026, 8, 1, 8, 0, 0, TimeSpan.Zero), true, 1_000, 100),
            CreateRecording(device, "long-large", new DateTimeOffset(2026, 8, 1, 10, 0, 0, TimeSpan.Zero), true, 10_000, 1_000));
        SetRowVersions(context);
        await context.SaveChangesAsync(TestContext.Current.CancellationToken);

        var result = await new RecordingRepository(context).QueryAsync(
            new RecordingQuery(Sort: sort),
            TestContext.Current.CancellationToken);

        Assert.Equal(expectedFirst, result.Items[0].ClientRecordingId);
    }

    [Fact]
    public async Task DeviceNameSortAndStableSecondaryOrderKeepPaginationDeterministic()
    {
        await using var context = CreateContext();
        var beta = CreateDevice("beta", "Beta Camera");
        var alpha = CreateDevice("alpha", "Alpha Camera");
        context.Devices.AddRange(beta, alpha);
        var instant = new DateTimeOffset(2026, 8, 1, 10, 0, 0, TimeSpan.Zero);
        context.Recordings.AddRange(
            CreateRecording(beta, "beta", instant, true, 1_000, 100, Guid.Parse("ffffffff-ffff-ffff-ffff-ffffffffffff")),
            CreateRecording(alpha, "alpha-2", instant, true, 1_000, 100, Guid.Parse("22222222-2222-2222-2222-222222222222")),
            CreateRecording(alpha, "alpha-1", instant, true, 1_000, 100, Guid.Parse("11111111-1111-1111-1111-111111111111")));
        SetRowVersions(context);
        await context.SaveChangesAsync(TestContext.Current.CancellationToken);
        var repository = new RecordingRepository(context);

        var first = await repository.QueryAsync(new RecordingQuery(Sort: RecordingSort.DeviceName, Page: 1, PageSize: 2), TestContext.Current.CancellationToken);
        var second = await repository.QueryAsync(new RecordingQuery(Sort: RecordingSort.DeviceName, Page: 2, PageSize: 2), TestContext.Current.CancellationToken);

        Assert.Equal(["alpha-1", "alpha-2"], first.Items.Select(item => item.ClientRecordingId));
        Assert.Equal("beta", Assert.Single(second.Items).ClientRecordingId);
        Assert.Equal(3, first.TotalCount);
    }

    [Theory]
    [InlineData(RecordingTimeLevel.Year, null, null, 1, 3)]
    [InlineData(RecordingTimeLevel.Month, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 2, 3)]
    [InlineData(RecordingTimeLevel.Week, "2026-08-01T00:00:00Z", "2026-09-01T00:00:00Z", 2, 2)]
    [InlineData(RecordingTimeLevel.Day, "2026-08-01T00:00:00Z", "2026-09-01T00:00:00Z", 2, 2)]
    [InlineData(RecordingTimeLevel.Hour, "2026-08-01T00:00:00Z", "2026-08-02T00:00:00Z", 1, 1)]
    public async Task TimeAggregationGroupsWithoutReturningRecordingRows(
        RecordingTimeLevel level,
        string? start,
        string? end,
        int expectedBuckets,
        int expectedRecordings)
    {
        await using var context = CreateContext();
        var selected = CreateDevice("selected", "Selected Camera");
        var other = CreateDevice("other", "Other Camera");
        context.Devices.AddRange(selected, other);
        context.Recordings.AddRange(
            CreateRecording(selected, "aug-8", new DateTimeOffset(2026, 8, 1, 8, 0, 0, TimeSpan.Zero), true, 1_000, 100),
            CreateRecording(selected, "aug-9", new DateTimeOffset(2026, 8, 2, 9, 0, 0, TimeSpan.Zero), true, 1_000, 200),
            CreateRecording(selected, "jul", new DateTimeOffset(2026, 7, 31, 8, 0, 0, TimeSpan.Zero), true, 1_000, 300),
            CreateRecording(other, "excluded", new DateTimeOffset(2026, 8, 1, 8, 0, 0, TimeSpan.Zero), true, 1_000, 900));
        SetRowVersions(context);
        await context.SaveChangesAsync(TestContext.Current.CancellationToken);

        var result = await new RecordingRepository(context).AggregateTimeAsync(
            new RecordingTimeQuery(
                level,
                start is null ? null : DateTimeOffset.Parse(start, CultureInfo.InvariantCulture),
                end is null ? null : DateTimeOffset.Parse(end, CultureInfo.InvariantCulture),
                DeviceIds: [selected.Id.Value]),
            TestContext.Current.CancellationToken);

        Assert.Equal(expectedBuckets, result.Count);
        Assert.Equal(expectedRecordings, result.Sum(bucket => bucket.RecordingCount));
        Assert.DoesNotContain(result, bucket => bucket.TotalSizeBytes >= 900);
    }

    [Theory]
    [InlineData(RecordingTimeLevel.Year, null, null, 1, 3)]
    [InlineData(RecordingTimeLevel.Month, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 2, 3)]
    [InlineData(RecordingTimeLevel.Week, "2026-08-01T00:00:00Z", "2026-09-01T00:00:00Z", 2, 2)]
    [InlineData(RecordingTimeLevel.Day, "2026-08-01T00:00:00Z", "2026-09-01T00:00:00Z", 2, 2)]
    [InlineData(RecordingTimeLevel.Hour, "2026-08-01T00:00:00Z", "2026-08-02T00:00:00Z", 1, 1)]
    public async Task SqliteTimeAggregationUsesProviderNativeDateBuckets(
        RecordingTimeLevel level,
        string? start,
        string? end,
        int expectedBuckets,
        int expectedRecordings)
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync(TestContext.Current.CancellationToken);
        await using var context = new SentriCamDbContext(
            new DbContextOptionsBuilder<SentriCamDbContext>()
                .UseSqlite(connection)
                .Options);
        await context.Database.EnsureCreatedAsync(TestContext.Current.CancellationToken);
        var selected = CreateDevice("sqlite-selected", "SQLite Camera");
        var other = CreateDevice("sqlite-other", "Other Camera");
        context.Devices.AddRange(selected, other);
        context.Recordings.AddRange(
            CreateRecording(selected, "sqlite-aug-8", new DateTimeOffset(2026, 8, 1, 8, 0, 0, TimeSpan.Zero), true, 1_000, 100),
            CreateRecording(selected, "sqlite-aug-9", new DateTimeOffset(2026, 8, 2, 9, 0, 0, TimeSpan.Zero), true, 1_000, 200),
            CreateRecording(selected, "sqlite-jul", new DateTimeOffset(2026, 7, 31, 8, 0, 0, TimeSpan.Zero), true, 1_000, 300),
            CreateRecording(other, "sqlite-excluded", new DateTimeOffset(2026, 8, 1, 8, 0, 0, TimeSpan.Zero), true, 1_000, 900));
        await context.SaveChangesAsync(TestContext.Current.CancellationToken);

        var result = await new RecordingRepository(context).AggregateTimeAsync(
            new RecordingTimeQuery(
                level,
                start is null ? null : DateTimeOffset.Parse(start, CultureInfo.InvariantCulture),
                end is null ? null : DateTimeOffset.Parse(end, CultureInfo.InvariantCulture),
                DeviceIds: [selected.Id.Value]),
            TestContext.Current.CancellationToken);

        Assert.Equal(expectedBuckets, result.Count);
        Assert.Equal(expectedRecordings, result.Sum(bucket => bucket.RecordingCount));
        Assert.DoesNotContain(result, bucket => bucket.TotalSizeBytes >= 900);
    }

    [Fact]
    public async Task SqliteTimeAggregationAppliesAllSharedFiltersServerSide()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync(TestContext.Current.CancellationToken);
        await using var context = new SentriCamDbContext(
            new DbContextOptionsBuilder<SentriCamDbContext>()
                .UseSqlite(connection)
                .Options);
        await context.Database.EnsureCreatedAsync(TestContext.Current.CancellationToken);
        var selected = CreateDevice("sqlite-filter-selected", "Rear Camera");
        var other = CreateDevice("sqlite-filter-other", "Other Camera");
        context.Devices.AddRange(selected, other);
        var match = CreateRecording(
            selected,
            "sqlite-filter-match",
            new DateTimeOffset(2026, 8, 1, 9, 15, 0, TimeSpan.Zero),
            false,
            90_000,
            5_000);
        match.RequestThumbnailRegeneration();
        context.Recordings.AddRange(
            match,
            CreateRecording(selected, "sqlite-filter-motion", new DateTimeOffset(2026, 8, 1, 9, 30, 0, TimeSpan.Zero), true, 90_000, 5_000),
            CreateRecording(selected, "sqlite-filter-late", new DateTimeOffset(2026, 8, 1, 11, 0, 0, TimeSpan.Zero), false, 90_000, 5_000),
            CreateRecording(other, "sqlite-filter-other", new DateTimeOffset(2026, 8, 1, 9, 15, 0, TimeSpan.Zero), false, 90_000, 5_000));
        await context.SaveChangesAsync(TestContext.Current.CancellationToken);

        var result = await new RecordingRepository(context).AggregateTimeAsync(
            new RecordingTimeQuery(
                RecordingTimeLevel.Hour,
                ParentStartUtc: new DateTimeOffset(2026, 8, 1, 0, 0, 0, TimeSpan.Zero),
                ParentEndUtc: new DateTimeOffset(2026, 8, 2, 0, 0, 0, TimeSpan.Zero),
                DeviceIds: [selected.Id.Value],
                Search: "Rear",
                Source: RecordingSourceFilter.Manual,
                DateFromUtc: new DateTimeOffset(2026, 8, 1, 0, 0, 0, TimeSpan.Zero),
                DateToUtc: new DateTimeOffset(2026, 8, 2, 0, 0, 0, TimeSpan.Zero),
                HourFrom: 9,
                HourTo: 10,
                MinimumDurationMilliseconds: 80_000,
                MaximumDurationMilliseconds: 100_000,
                MinimumSizeBytes: 4_000,
                MaximumSizeBytes: 6_000,
                ThumbnailState: RecordingThumbnailState.Pending),
            TestContext.Current.CancellationToken);

        var bucket = Assert.Single(result);
        Assert.Equal(9, bucket.Hour);
        Assert.Equal(1, bucket.RecordingCount);
        Assert.Equal(5_000, bucket.TotalSizeBytes);
    }

    private static SentriCamDbContext CreateContext() => new(
        new DbContextOptionsBuilder<SentriCamDbContext>()
            .UseInMemoryDatabase(Guid.NewGuid().ToString("N"))
            .Options);

    private static void SetRowVersions(SentriCamDbContext context)
    {
        foreach (var entry in context.ChangeTracker.Entries<Device>())
        {
            entry.Property<byte[]>("RowVersion").CurrentValue = [1];
        }
        foreach (var entry in context.ChangeTracker.Entries<Recording>())
        {
            entry.Property<byte[]>("RowVersion").CurrentValue = [1];
        }
    }

    private static Device CreateDevice(string installationId, string name) =>
        Device.Register(
            name,
            DeviceTestFactory.CreateIdentity(installationId),
            [new DeviceCapabilityDefinition("Camera", "1", true)],
            DeviceTestFactory.Now);

    private static Recording CreateRecording(
        Device device,
        string clientId,
        DateTimeOffset createdUtc,
        bool motion,
        long duration,
        long size,
        Guid? id = null) =>
        Recording.Create(
            id ?? Guid.NewGuid(),
            device.Id,
            clientId,
            $"session-{clientId}",
            $"{clientId}.mp4",
            "video/mp4",
            duration,
            size,
            createdUtc,
            createdUtc.AddMinutes(1),
            motion,
            !motion,
            new string('a', 64),
            $"{device.Id.Value:N}/{clientId}.mp4");
}
