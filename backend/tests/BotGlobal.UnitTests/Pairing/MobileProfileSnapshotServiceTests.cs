using BotGlobal.Pairing.Application.Profiles;
using BotGlobal.Pairing.Domain;
using BotGlobal.Pairing.Infrastructure.Persistence;
using BotGlobal.Pairing.Security;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.UnitTests.Pairing;

public sealed class MobileProfileSnapshotServiceTests
{
    private static readonly DateTimeOffset PublishedAt =
        new(2026, 9, 4, 8, 0, 0, TimeSpan.Zero);

    [Fact]
    public async Task PairedSubjectCanPublishAndReadMinimizedSnapshot()
    {
        await using var db = CreateContext();
        var applicationId = Guid.NewGuid();
        AddDevice(db, applicationId, "subject-a");
        await db.SaveChangesAsync();
        var service = CreateService(db);

        var result = await service.PublishAsync(
            applicationId,
            Request("subject-a"),
            CancellationToken.None);
        var profile = await service.ReadAsync(
            applicationId,
            "subject-a",
            CancellationToken.None);

        Assert.Equal(MobileProfilePublishOutcome.Created, result.Outcome);
        Assert.NotNull(profile);
        Assert.Equal("Synthetic User", profile.DisplayName);
        Assert.Equal("Specialist", profile.JobTitle);
        Assert.Equal("Operations", profile.OrganizationUnit);
        Assert.Equal(1, profile.Version);
        Assert.Equal(PublishedAt, profile.UpdatedAtUtc);
    }

    [Fact]
    public async Task PublisherCannotWriteUnknownOrOtherApplicationSubject()
    {
        await using var db = CreateContext();
        var applicationA = Guid.NewGuid();
        var applicationB = Guid.NewGuid();
        AddDevice(db, applicationB, "shared-subject");
        await db.SaveChangesAsync();
        var service = CreateService(db);

        var result = await service.PublishAsync(
            applicationA,
            Request("shared-subject"),
            CancellationToken.None);

        Assert.Equal(MobileProfilePublishOutcome.SubjectNotPaired, result.Outcome);
        Assert.Empty(db.ProfileSnapshots);
    }

    [Theory]
    [InlineData("<script>alert(1)</script>")]
    [InlineData("bad\nvalue")]
    public async Task UnsafeDisplayFieldsAreRejected(string displayName)
    {
        await using var db = CreateContext();
        var applicationId = Guid.NewGuid();
        AddDevice(db, applicationId, "subject-a");
        await db.SaveChangesAsync();

        await Assert.ThrowsAsync<ArgumentException>(
            () => CreateService(db).PublishAsync(
                applicationId,
                Request("subject-a") with { DisplayName = displayName },
                CancellationToken.None));
    }

    [Fact]
    public async Task OversizedFieldAndInvalidVersionAreRejected()
    {
        await using var db = CreateContext();
        var applicationId = Guid.NewGuid();
        AddDevice(db, applicationId, "subject-a");
        await db.SaveChangesAsync();
        var service = CreateService(db);

        await Assert.ThrowsAsync<ArgumentException>(
            () => service.PublishAsync(
                applicationId,
                Request("subject-a") with
                {
                    OrganizationUnit = new string(
                        'x',
                        MobileProfileSnapshot.OrganizationUnitMaxLength + 1)
                },
                CancellationToken.None));
        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(
            () => service.PublishAsync(
                applicationId,
                Request("subject-a") with { Version = 0 },
                CancellationToken.None));
    }

    [Fact]
    public async Task NewerVersionReplacesOlderAndStaleVersionCannotOverwrite()
    {
        await using var db = CreateContext();
        var applicationId = Guid.NewGuid();
        AddDevice(db, applicationId, "subject-a");
        await db.SaveChangesAsync();
        var service = CreateService(db);

        await service.PublishAsync(
            applicationId,
            Request("subject-a"),
            CancellationToken.None);
        var updated = await service.PublishAsync(
            applicationId,
            Request("subject-a") with
            {
                DisplayName = "Updated Synthetic User",
                Version = 3,
                PublishedAtUtc = PublishedAt.AddMinutes(2)
            },
            CancellationToken.None);
        var stale = await service.PublishAsync(
            applicationId,
            Request("subject-a") with
            {
                DisplayName = "Stale Synthetic User",
                Version = 2,
                PublishedAtUtc = PublishedAt.AddMinutes(1)
            },
            CancellationToken.None);
        var profile = await service.ReadAsync(
            applicationId,
            "subject-a",
            CancellationToken.None);

        Assert.Equal(MobileProfilePublishOutcome.Updated, updated.Outcome);
        Assert.Equal(MobileProfilePublishOutcome.StaleIgnored, stale.Outcome);
        Assert.Equal("Updated Synthetic User", profile!.DisplayName);
        Assert.Equal(3, profile.Version);
    }

    [Fact]
    public async Task SameVersionIsIdempotentButCannotChangeContent()
    {
        await using var db = CreateContext();
        var applicationId = Guid.NewGuid();
        AddDevice(db, applicationId, "subject-a");
        await db.SaveChangesAsync();
        var service = CreateService(db);
        var request = Request("subject-a");

        await service.PublishAsync(applicationId, request, CancellationToken.None);
        var repeated = await service.PublishAsync(
            applicationId,
            request,
            CancellationToken.None);
        var conflict = await service.PublishAsync(
            applicationId,
            request with { DisplayName = "Different Synthetic User" },
            CancellationToken.None);

        Assert.Equal(MobileProfilePublishOutcome.Unchanged, repeated.Outcome);
        Assert.Equal(MobileProfilePublishOutcome.VersionConflict, conflict.Outcome);
        Assert.Single(db.ProfileSnapshots);
    }

    [Fact]
    public async Task ReadsAreApplicationAndSubjectIsolatedAndMissingIsSafe()
    {
        await using var db = CreateContext();
        var applicationA = Guid.NewGuid();
        var applicationB = Guid.NewGuid();
        AddDevice(db, applicationA, "subject-a");
        AddDevice(db, applicationB, "subject-b");
        await db.SaveChangesAsync();
        var service = CreateService(db);
        await service.PublishAsync(
            applicationA,
            Request("subject-a"),
            CancellationToken.None);

        Assert.Null(await service.ReadAsync(
            applicationA,
            "subject-b",
            CancellationToken.None));
        Assert.Null(await service.ReadAsync(
            applicationB,
            "subject-a",
            CancellationToken.None));
        Assert.NotNull(await service.ReadAsync(
            applicationA,
            "subject-a",
            CancellationToken.None));
    }

    [Fact]
    public async Task RevokedDeviceCredentialCannotAuthenticateForProfileRead()
    {
        await using var db = CreateContext();
        var credentials = new MobileDeviceCredentialService();
        var issued = credentials.Generate();
        var device = AddDevice(
            db,
            Guid.NewGuid(),
            "subject-a",
            issued.Hash);
        await db.SaveChangesAsync();
        var authenticator = new MobileDeviceAuthenticator(db, credentials);

        Assert.NotNull(await authenticator.AuthenticateAsync(
            issued.PlainText,
            CancellationToken.None));

        device.Revoke(PublishedAt);
        await db.SaveChangesAsync();

        Assert.Null(await authenticator.AuthenticateAsync(
            issued.PlainText,
            CancellationToken.None));
    }

    private static PublishMobileProfileSnapshotRequest Request(string subject) =>
        new(
            subject,
            "Synthetic User",
            "Specialist",
            "Operations",
            1,
            PublishedAt);

    private static MobileProfileSnapshotService CreateService(PairingDbContext db) =>
        new(db, new FixedTimeProvider());

    private static MobileDevice AddDevice(
        PairingDbContext db,
        Guid applicationId,
        string subject,
        byte[]? credentialHash = null)
    {
        var device = new MobileDevice(
            Guid.NewGuid(),
            applicationId,
            subject,
            $"installation-{Guid.NewGuid():N}",
            "android",
            "Test device",
            "1.0",
            credentialHash ?? Guid.NewGuid().ToByteArray(),
            PublishedAt.AddDays(-1));
        db.Devices.Add(device);
        return device;
    }

    private static PairingDbContext CreateContext()
    {
        var options = new DbContextOptionsBuilder<PairingDbContext>()
            .UseInMemoryDatabase($"mobile-profile-{Guid.NewGuid():N}")
            .Options;
        return new PairingDbContext(options);
    }

    private sealed class FixedTimeProvider : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => PublishedAt.AddMinutes(5);
    }
}
