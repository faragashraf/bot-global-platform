using BotGlobal.Contracts.Notifications;
using BotGlobal.Pairing.Application;
using BotGlobal.Pairing.Application.MobileDevices;
using BotGlobal.Pairing.Domain;
using BotGlobal.Pairing.Infrastructure.Persistence;
using BotGlobal.Pairing.Security;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.UnitTests.Pairing;

public sealed class MobileDeviceEnrollmentServiceTests
{
    [Fact]
    public async Task Authenticated_application_and_subject_are_resolved_server_side()
    {
        var applicationId = Guid.NewGuid();
        await using var db = CreateContext();
        var applications = new ApplicationResolver(
            new PlatformClientDescriptor(
                applicationId,
                "nqrb",
                "NQRB",
                true));
        var service = CreateService(db, applications);

        var response = await service.EnrollAsync(
            "nqrb",
            "user:server-authoritative",
            Request("installation-1"),
            CancellationToken.None);

        var device = await db.Devices.SingleAsync();
        Assert.Equal(response.DeviceId, device.Id);
        Assert.Equal(applicationId, device.PlatformClientId);
        Assert.Equal("user:server-authoritative", device.ExternalSubjectId);
        Assert.Equal("nqrb", applications.RequestedClientKey);
        Assert.NotEmpty(response.Credential);
    }

    [Fact]
    public async Task Reenrollment_rotates_credential_without_duplicate_device()
    {
        var applicationId = Guid.NewGuid();
        await using var db = CreateContext();
        var applications = new ApplicationResolver(
            new PlatformClientDescriptor(applicationId, "nqrb", "NQRB", true));
        var service = CreateService(db, applications);

        var first = await service.EnrollAsync(
            "nqrb",
            "subject-one",
            Request("same-installation"),
            CancellationToken.None);
        var second = await service.EnrollAsync(
            "nqrb",
            "subject-two",
            Request("same-installation") with { DeviceName = "Updated device" },
            CancellationToken.None);

        var device = await db.Devices.SingleAsync();
        Assert.Equal(first.DeviceId, second.DeviceId);
        Assert.NotEqual(first.Credential, second.Credential);
        Assert.Equal("subject-two", device.ExternalSubjectId);
        Assert.Equal("Updated device", device.DeviceName);
        Assert.Equal(2, await db.DeviceAuditEntries.CountAsync());
    }

    [Fact]
    public async Task Same_installation_remains_isolated_between_applications()
    {
        await using var db = CreateContext();
        var appA = Guid.NewGuid();
        var appB = Guid.NewGuid();
        var resolver = new MultiApplicationResolver(
            new PlatformClientDescriptor(appA, "nqrb", "NQRB", true),
            new PlatformClientDescriptor(appB, "family-games", "Lamma", true));
        var service = CreateService(db, resolver);

        await service.EnrollAsync("nqrb", "subject", Request("same"), CancellationToken.None);
        await service.EnrollAsync("family-games", "subject", Request("same"), CancellationToken.None);

        Assert.Equal(2, await db.Devices.CountAsync());
        Assert.Contains(await db.Devices.ToArrayAsync(), item => item.PlatformClientId == appA);
        Assert.Contains(await db.Devices.ToArrayAsync(), item => item.PlatformClientId == appB);
    }

    [Fact]
    public async Task Unknown_or_disabled_application_is_rejected()
    {
        await using var db = CreateContext();
        var service = CreateService(db, new ApplicationResolver(null));

        await Assert.ThrowsAsync<MobileDeviceEnrollmentApplicationException>(
            () => service.EnrollAsync(
                "client-supplied-unknown",
                "subject",
                Request("installation"),
                CancellationToken.None));

        Assert.Empty(db.Devices);
    }

    [Fact]
    public void Enrollment_request_cannot_carry_application_or_subject_identity()
    {
        var properties = typeof(EnrollMobileDeviceRequest)
            .GetProperties()
            .Select(property => property.Name)
            .ToArray();

        Assert.Equal(
            [
                nameof(EnrollMobileDeviceRequest.InstallationId),
                nameof(EnrollMobileDeviceRequest.Platform),
                nameof(EnrollMobileDeviceRequest.DeviceName),
                nameof(EnrollMobileDeviceRequest.AppVersion)
            ],
            properties);
    }

    private static EnrollMobileDeviceRequest Request(string installationId) =>
        new(installationId, "android", "Test device", "1.0");

    private static MobileDeviceEnrollmentService CreateService(
        PairingDbContext db,
        IPlatformClientApplicationResolver applications) =>
        new(
            db,
            applications,
            new MobileDeviceCredentialService(),
            new MobileDeviceAuditRecorder(db),
            new FixedTimeProvider());

    private static PairingDbContext CreateContext()
    {
        var options = new DbContextOptionsBuilder<PairingDbContext>()
            .UseInMemoryDatabase($"mobile-device-enrollment-{Guid.NewGuid():N}")
            .Options;
        return new PairingDbContext(options);
    }

    private sealed class ApplicationResolver(PlatformClientDescriptor? descriptor)
        : IPlatformClientApplicationResolver
    {
        public string? RequestedClientKey { get; private set; }

        public Task<PlatformClientDescriptor?> FindByClientKeyAsync(
            string clientKey,
            CancellationToken cancellationToken)
        {
            RequestedClientKey = clientKey;
            return Task.FromResult(descriptor);
        }
    }

    private sealed class MultiApplicationResolver(
        params PlatformClientDescriptor[] descriptors)
        : IPlatformClientApplicationResolver
    {
        public Task<PlatformClientDescriptor?> FindByClientKeyAsync(
            string clientKey,
            CancellationToken cancellationToken) =>
            Task.FromResult(
                descriptors.SingleOrDefault(item => item.ClientKey == clientKey));
    }

    private sealed class FixedTimeProvider : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() =>
            new(2026, 8, 31, 12, 0, 0, TimeSpan.Zero);
    }
}
