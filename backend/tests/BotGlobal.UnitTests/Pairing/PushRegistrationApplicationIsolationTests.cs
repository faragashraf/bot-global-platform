using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Notifications;
using BotGlobal.Pairing.Application;
using BotGlobal.Pairing.Application.AdminDevicePairings;
using BotGlobal.Pairing.Application.Notifications;
using BotGlobal.Pairing.Application.PushRegistrations;
using BotGlobal.Pairing.Domain;
using BotGlobal.Pairing.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.UnitTests.Pairing;

public sealed class PushRegistrationApplicationIsolationTests
{
    [Fact]
    public async Task Push_destination_cannot_be_resolved_cross_application()
    {
        var appA = Guid.NewGuid();
        var appB = Guid.NewGuid();
        await using var db = CreateContext();
        var deviceA = Device(appA, "shared-installation");
        var deviceB = Device(appB, "shared-installation");
        db.Devices.AddRange(deviceA, deviceB);
        db.PushRegistrations.AddRange(
            new MobilePushRegistration(
                deviceA.Id,
                "fcm",
                "token-a",
                DateTimeOffset.UtcNow),
            new MobilePushRegistration(
                deviceB.Id,
                "fcm",
                "token-b",
                DateTimeOffset.UtcNow));
        await db.SaveChangesAsync();
        var resolver = new MobilePushDestinationResolver(db);

        var own = await resolver.ResolveActiveAsync(
            new NotificationApplicationContext(appA),
            deviceA.Id,
            "fcm",
            CancellationToken.None);
        var crossApplication = await resolver.ResolveActiveAsync(
            new NotificationApplicationContext(appA),
            deviceB.Id,
            "fcm",
            CancellationToken.None);

        Assert.Equal("token-a", own!.RegistrationToken);
        Assert.Null(crossApplication);
    }

    [Fact]
    public async Task Registration_scope_survives_revocation_and_same_installation_is_separate_per_app()
    {
        var appA = Guid.NewGuid();
        var appB = Guid.NewGuid();
        var now = new DateTimeOffset(
            2026,
            8,
            30,
            12,
            0,
            0,
            TimeSpan.Zero);
        await using var db = CreateContext();
        var deviceA = Device(appA, "same-physical-installation", now);
        var deviceB = Device(appB, "same-physical-installation", now);
        db.Devices.AddRange(deviceA, deviceB);
        await db.SaveChangesAsync();
        var service = new MobilePushRegistrationService(
            db,
            new MobileDeviceAuditRecorder(db),
            new FixedTimeProvider(now));

        var result = await service.RegisterAsync(
            new NotificationApplicationContext(appA),
            deviceA.Id,
            new RegisterMobilePushRequest("fcm", "token-a"),
            CancellationToken.None);

        Assert.Equal(appA, result.ApplicationId);
        await Assert.ThrowsAsync<InvalidOperationException>(
            () => service.RegisterAsync(
                new NotificationApplicationContext(appB),
                deviceA.Id,
                new RegisterMobilePushRequest("fcm", "spoofed-token"),
                CancellationToken.None));

        deviceA.Revoke(now.AddMinutes(1));
        await db.SaveChangesAsync();
        await service.InvalidateAllAsync(
            deviceA.Id,
            CancellationToken.None);

        var registration = await db.PushRegistrations.SingleAsync();
        Assert.NotNull(registration.InvalidatedAtUtc);
        Assert.All(
            await db.DeviceAuditEntries
                .Where(entry => entry.MobileDeviceId == deviceA.Id)
                .ToArrayAsync(),
            entry => Assert.Equal(appA, entry.PlatformClientId));

        var resolver = new PairingMobileNotificationRecipientResolver(db);
        var appADevices = await resolver.ResolveActiveDevicesAsync(
            new NotificationApplicationContext(appA),
            "subject",
            CancellationToken.None);
        var appBDevices = await resolver.ResolveActiveDevicesAsync(
            new NotificationApplicationContext(appB),
            "subject",
            CancellationToken.None);

        Assert.Empty(appADevices);
        Assert.Single(appBDevices);
        Assert.Equal(deviceB.Id, appBDevices[0].DeviceId);
    }

    [Fact]
    public async Task App_scoped_admin_query_excludes_other_apps_while_platform_global_scope_is_explicit()
    {
        var appA = Guid.NewGuid();
        var appB = Guid.NewGuid();
        await using var db = CreateContext();
        db.Devices.AddRange(
            Device(appA, "installation-a"),
            Device(appB, "installation-b"));
        await db.SaveChangesAsync();
        var service = new AdminDevicePairingService(
            db,
            new DescriptorReader(appA, appB),
            new EmptyNotificationLogs(),
            new MobileDeviceAuditRecorder(db),
            TimeProvider.System);

        var scoped = await service.ListAsync(
            ApplicationAdministrationScope.ForApplication(appA),
            CancellationToken.None);
        var platformGlobal = await service.ListAsync(
            ApplicationAdministrationScope.PlatformGlobal,
            CancellationToken.None);

        Assert.Single(scoped);
        Assert.All(
            scoped,
            device => Assert.Equal(appA, device.PlatformClientId));
        Assert.Equal(2, platformGlobal.Count);
        Assert.Contains(
            platformGlobal,
            device => device.PlatformClientId == appB);
    }

    [Fact]
    public async Task Unrecognized_admin_application_scope_is_rejected()
    {
        await using var db = CreateContext();
        var service = new AdminDevicePairingService(
            db,
            new DescriptorReader(),
            new EmptyNotificationLogs(),
            new MobileDeviceAuditRecorder(db),
            TimeProvider.System);

        await Assert.ThrowsAsync<AdminDeviceApplicationScopeException>(
            () => service.ListAsync(
                ApplicationAdministrationScope.ForApplication(
                    Guid.NewGuid()),
                CancellationToken.None));
    }

    private static MobileDevice Device(
        Guid applicationId,
        string installationId,
        DateTimeOffset? now = null) =>
        new(
            Guid.NewGuid(),
            applicationId,
            "subject",
            installationId,
            "android",
            "Test device",
            "1.0",
            Guid.NewGuid().ToByteArray()
                .Concat(Guid.NewGuid().ToByteArray())
                .ToArray(),
            now ?? DateTimeOffset.UtcNow);

    private static PairingDbContext CreateContext()
    {
        var options = new DbContextOptionsBuilder<PairingDbContext>()
            .UseInMemoryDatabase(
                $"push-application-isolation-{Guid.NewGuid():N}")
            .Options;
        return new PairingDbContext(options);
    }

    private sealed class DescriptorReader(params Guid[] applicationIds)
        : IPlatformClientDescriptorReader
    {
        public Task<PlatformClientDescriptor?> FindAsync(
            Guid platformClientId,
            CancellationToken cancellationToken)
        {
            var descriptor = applicationIds.Contains(platformClientId)
                ? new PlatformClientDescriptor(
                    platformClientId,
                    $"app-{platformClientId:N}",
                    "Application",
                    true)
                : null;

            return Task.FromResult(descriptor);
        }
    }

    private sealed class EmptyNotificationLogs
        : INotificationDeviceLogReader
    {
        public Task<IReadOnlyList<MobileDeviceDeliveryLogEntry>>
            ReadForDeviceAsync(
                NotificationApplicationContext application,
                Guid mobileDeviceId,
                CancellationToken cancellationToken) =>
            Task.FromResult<IReadOnlyList<MobileDeviceDeliveryLogEntry>>(
                []);

        public Task<int> PurgeForDeviceAsync(
            NotificationApplicationContext application,
            Guid mobileDeviceId,
            CancellationToken cancellationToken) =>
            Task.FromResult(0);
    }

    private sealed class FixedTimeProvider(DateTimeOffset now)
        : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }
}
