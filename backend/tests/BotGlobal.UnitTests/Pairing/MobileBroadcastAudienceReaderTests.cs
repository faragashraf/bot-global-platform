using BotGlobal.Pairing.Application.Notifications;
using BotGlobal.Contracts.Notifications;
using BotGlobal.Pairing.Domain;
using BotGlobal.Pairing.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.UnitTests.Pairing;

public sealed class MobileBroadcastAudienceReaderTests
{
    [Fact]
    public async Task Snapshot_is_isolated_by_platform_and_excludes_future_or_already_revoked_devices()
    {
        var asOf = new DateTimeOffset(2026, 8, 21, 10, 0, 0, TimeSpan.Zero);
        var platformA = Guid.NewGuid();
        var platformB = Guid.NewGuid();

        await using var db = CreateContext();
        var active = Device(platformA, "subject-1", asOf.AddDays(-2));
        var revokedAfter = Device(platformA, "subject-2", asOf.AddDays(-2));
        revokedAfter.Revoke(asOf.AddMinutes(1));
        var revokedBefore = Device(platformA, "subject-3", asOf.AddDays(-2));
        revokedBefore.Revoke(asOf);
        var future = Device(platformA, "subject-4", asOf.AddSeconds(1));
        var otherPlatform = Device(platformB, "subject-5", asOf.AddDays(-2));

        db.Devices.AddRange(active, revokedAfter, revokedBefore, future, otherPlatform);
        await db.SaveChangesAsync();

        var reader = new PairingMobileBroadcastAudienceReader(db);
        var application = new NotificationApplicationContext(platformA);
        var preview = await reader.PreviewAsync(application, asOf, CancellationToken.None);
        var page = await reader.ReadPageAsync(application, asOf, null, 100, CancellationToken.None);

        Assert.Equal(2, preview.DistinctExternalSubjectCount);
        Assert.Equal(2, preview.ActiveDeviceCount);
        Assert.Equal(
            new[] { active.Id, revokedAfter.Id }.OrderBy(id => id),
            page.Devices.Select(device => device.DeviceId).OrderBy(id => id));
        Assert.DoesNotContain(page.Devices, device => device.DeviceId == future.Id);
        Assert.DoesNotContain(page.Devices, device => device.DeviceId == otherPlatform.Id);
    }

    [Fact]
    public async Task Multiple_devices_for_one_subject_are_counted_as_separate_recipients()
    {
        var asOf = DateTimeOffset.UtcNow;
        var platform = Guid.NewGuid();
        await using var db = CreateContext();
        var first = Device(platform, "same-subject", asOf.AddDays(-1));
        var second = Device(platform, "same-subject", asOf.AddHours(-1));
        db.Devices.AddRange(first, second);
        db.PushRegistrations.Add(new MobilePushRegistration(
            first.Id,
            "fcm",
            "test-token-never-persisted-by-notifications",
            asOf.AddMinutes(-1)));
        await db.SaveChangesAsync();

        var reader = new PairingMobileBroadcastAudienceReader(db);
        var preview = await reader.PreviewAsync(
            new NotificationApplicationContext(platform),
            asOf,
            CancellationToken.None);

        Assert.Equal(1, preview.DistinctExternalSubjectCount);
        Assert.Equal(2, preview.ActiveDeviceCount);
        Assert.Equal(1, preview.PushCapableDeviceCount);
    }

    [Fact]
    public async Task Paged_reads_are_deterministic_and_resume_after_device_cursor()
    {
        var asOf = DateTimeOffset.UtcNow;
        var platform = Guid.NewGuid();
        await using var db = CreateContext();
        db.Devices.AddRange(
            Enumerable.Range(1, 5)
                .Select(index => Device(
                    platform,
                    $"subject-{index}",
                    asOf.AddDays(-1))));
        await db.SaveChangesAsync();

        var reader = new PairingMobileBroadcastAudienceReader(db);
        var application = new NotificationApplicationContext(platform);
        var first = await reader.ReadPageAsync(application, asOf, null, 2, CancellationToken.None);
        var second = await reader.ReadPageAsync(
            application,
            asOf,
            first.Devices[^1].DeviceId,
            2,
            CancellationToken.None);

        Assert.True(first.HasMore);
        Assert.DoesNotContain(
            second.Devices,
            candidate => first.Devices.Any(previous => previous.DeviceId == candidate.DeviceId));
        Assert.Equal(
            first.Devices.OrderBy(device => device.DeviceId),
            first.Devices);
    }

    [Fact]
    public void Device_cursor_expression_translates_for_sql_server()
    {
        var options = new DbContextOptionsBuilder<PairingDbContext>()
            .UseSqlServer(
                "Server=localhost;Database=PairingAudienceTranslation;Trusted_Connection=True;TrustServerCertificate=True")
            .Options;
        using var db = new PairingDbContext(options);
        var cursor = Guid.NewGuid();

        var sql = db.Devices
            .Where(device => device.Id.CompareTo(cursor) > 0)
            .OrderBy(device => device.Id)
            .Take(101)
            .ToQueryString();

        Assert.Contains("ORDER BY", sql, StringComparison.OrdinalIgnoreCase);
    }

    private static MobileDevice Device(
        Guid platformClientId,
        string subject,
        DateTimeOffset createdAtUtc)
    {
        return new MobileDevice(
            Guid.NewGuid(),
            platformClientId,
            subject,
            $"installation-{Guid.NewGuid():N}",
            "android",
            "Test device",
            "1.0",
            Random.Shared.GetItems<byte>([1, 2, 3, 4], 32),
            createdAtUtc);
    }

    private static PairingDbContext CreateContext()
    {
        var options = new DbContextOptionsBuilder<PairingDbContext>()
            .UseInMemoryDatabase($"audience-{Guid.NewGuid():N}")
            .Options;
        return new PairingDbContext(options);
    }
}
