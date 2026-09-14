using BotGlobal.Contracts.Notifications;
using BotGlobal.Pairing.Application;
using BotGlobal.Pairing.Application.PushRegistrations;
using BotGlobal.Pairing.Domain;
using BotGlobal.Pairing.Infrastructure.Persistence;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;

namespace BotGlobal.UnitTests.Pairing;

public sealed class PushDestinationLifecycleTests
{
    [Theory]
    [InlineData("replacement-route")]
    [InlineData("Original-route")]
    public async Task Late_rejection_cannot_invalidate_a_replacement_even_with_case_only_difference(string replacement)
    {
        await using var database = await Database.CreateAsync();
        await using (var refresh = database.Context())
            await Service(refresh).RegisterAsync(database.Application, database.DeviceId,
                new RegisterMobilePushRequest("fcm", replacement), CancellationToken.None);

        await using (var rejection = database.Context())
            await Service(rejection).InvalidateAsync(database.Application, database.DeviceId,
                "fcm", "original-route", "provider-unregistered", CancellationToken.None);

        await using var verify = database.Context();
        var active = await new MobilePushDestinationResolver(verify).ResolveActiveAsync(
            database.Application, database.DeviceId, "fcm", CancellationToken.None);
        Assert.Equal(replacement, active!.RegistrationToken);
        Assert.Empty(await verify.DeviceAuditEntries.Where(entry =>
            entry.Kind == MobileDeviceAuditKinds.PushInvalidated).ToArrayAsync());
    }

    [Fact]
    public async Task Refresh_clears_invalidation_that_arrives_after_its_tracked_read()
    {
        await using var database = await Database.CreateAsync();
        var concurrentRejection = new BeforeSave(async () =>
        {
            await using var rejection = database.Context();
            await Service(rejection).InvalidateAsync(database.Application, database.DeviceId,
                "fcm", "original-route", "provider-unregistered", CancellationToken.None);
        });
        await using (var refresh = database.Context(concurrentRejection))
            await Service(refresh).RegisterAsync(database.Application, database.DeviceId,
                new RegisterMobilePushRequest("fcm", "replacement-route"), CancellationToken.None);

        Assert.True(concurrentRejection.Called);
        await using var verify = database.Context();
        var registration = await verify.PushRegistrations.SingleAsync();
        Assert.Equal("replacement-route", registration.RegistrationToken);
        Assert.Null(registration.InvalidatedAtUtc);
    }

    [Fact]
    public async Task Invalidation_is_scoped_atomic_and_idempotent_and_registration_can_recover()
    {
        await using var database = await Database.CreateAsync();
        await using (var db = database.Context())
        {
            var service = Service(db);
            await service.InvalidateAsync(new NotificationApplicationContext(Guid.NewGuid()), database.DeviceId,
                "fcm", "original-route", "provider-unregistered", CancellationToken.None);
            await service.InvalidateAsync(database.Application, database.DeviceId,
                "apns", "original-route", "provider-unregistered", CancellationToken.None);
            Assert.Null((await db.PushRegistrations.AsNoTracking().SingleAsync()).InvalidatedAtUtc);

            await service.InvalidateAsync(database.Application, database.DeviceId,
                "fcm", "original-route", "provider-unregistered", CancellationToken.None);
            await service.InvalidateAsync(database.Application, database.DeviceId,
                "fcm", "original-route", "provider-unregistered", CancellationToken.None);
            Assert.Single(await db.DeviceAuditEntries.ToArrayAsync());
            Assert.Null(await new MobilePushDestinationResolver(db).ResolveActiveAsync(
                database.Application, database.DeviceId, "fcm", CancellationToken.None));
        }

        await using var refresh = database.Context();
        await Service(refresh).RegisterAsync(database.Application, database.DeviceId,
            new RegisterMobilePushRequest("fcm", "replacement-route"), CancellationToken.None);
        Assert.NotNull(await new MobilePushDestinationResolver(refresh).ResolveActiveAsync(
            database.Application, database.DeviceId, "fcm", CancellationToken.None));
        Assert.All(await refresh.DeviceAuditEntries.ToArrayAsync(), entry =>
        {
            Assert.Equal(database.Application.ApplicationId, entry.PlatformClientId);
            Assert.DoesNotContain("original-route", entry.Detail ?? "");
            Assert.DoesNotContain("replacement-route", entry.Detail ?? "");
        });
    }

    [Fact]
    public async Task Invalidation_and_audit_roll_back_together_on_save_failure()
    {
        await using var database = await Database.CreateAsync();
        await using (var rejection = database.Context(new BeforeSave(() =>
                         throw new InvalidOperationException("Synthetic audit write failure"))))
        {
            await Assert.ThrowsAsync<InvalidOperationException>(() => Service(rejection).InvalidateAsync(
                database.Application, database.DeviceId, "fcm", "original-route",
                "provider-unregistered", CancellationToken.None));
        }

        await using var verify = database.Context();
        Assert.Null((await verify.PushRegistrations.SingleAsync()).InvalidatedAtUtc);
        Assert.Empty(await verify.DeviceAuditEntries.ToArrayAsync());
    }

    [Fact]
    public async Task Device_revoked_during_registration_cannot_leave_an_active_push_destination()
    {
        await using var database = await Database.CreateAsync();
        var concurrentRevocation = new AfterSave(async () =>
        {
            await using var revocation = database.Context();
            var device = await revocation.Devices.SingleAsync();
            device.Revoke(DateTimeOffset.UtcNow);
            await revocation.SaveChangesAsync();
        });

        await using (var registration = database.Context(concurrentRevocation))
        {
            await Assert.ThrowsAsync<InvalidOperationException>(() =>
                Service(registration).RegisterAsync(
                    database.Application,
                    database.DeviceId,
                    new RegisterMobilePushRequest("fcm", "late-route"),
                    CancellationToken.None));
        }

        Assert.True(concurrentRevocation.Called);
        await using var verify = database.Context();
        Assert.Null(await new MobilePushDestinationResolver(verify).ResolveActiveAsync(
            database.Application,
            database.DeviceId,
            "fcm",
            CancellationToken.None));
        Assert.NotNull((await verify.PushRegistrations.SingleAsync()).InvalidatedAtUtc);
    }

    private static MobilePushRegistrationService Service(PairingDbContext context) =>
        new(context, new MobileDeviceAuditRecorder(context), TimeProvider.System);

    // Real relational ExecuteUpdate/transaction checks in an isolated in-memory
    // SQLite database. This does not claim a SQL Server concurrency rehearsal.
    private sealed class Database : IAsyncDisposable
    {
        private readonly SqliteConnection connection = new("Data Source=:memory:");
        public NotificationApplicationContext Application { get; } = new(Guid.NewGuid());
        public Guid DeviceId { get; private set; }

        public static async Task<Database> CreateAsync()
        {
            var database = new Database();
            await database.connection.OpenAsync();
            database.connection.CreateCollation("Latin1_General_100_BIN2", string.CompareOrdinal);
            await using var db = database.Context();
            await db.Database.EnsureCreatedAsync();
            var device = new MobileDevice(Guid.NewGuid(), database.Application.ApplicationId,
                "synthetic-subject", "synthetic-installation", "android", "Synthetic device", "1.0",
                new byte[32], DateTimeOffset.UtcNow);
            database.DeviceId = device.Id;
            db.Devices.Add(device);
            db.PushRegistrations.Add(new MobilePushRegistration(device.Id, "fcm", "original-route", DateTimeOffset.UtcNow));
            await db.SaveChangesAsync();
            return database;
        }

        public PairingDbContext Context(params IInterceptor[] interceptors) => new(
            new DbContextOptionsBuilder<PairingDbContext>().UseSqlite(connection)
                .AddInterceptors(interceptors).Options);

        public ValueTask DisposeAsync() => connection.DisposeAsync();
    }

    private sealed class BeforeSave(Func<Task> action) : SaveChangesInterceptor
    {
        public bool Called { get; private set; }
        public override async ValueTask<InterceptionResult<int>> SavingChangesAsync(
            DbContextEventData eventData, InterceptionResult<int> result,
            CancellationToken cancellationToken = default)
        {
            if (!Called)
            {
                Called = true;
                await action();
            }
            return result;
        }
    }

    private sealed class AfterSave(Func<Task> action) : SaveChangesInterceptor
    {
        public bool Called { get; private set; }

        public override async ValueTask<int> SavedChangesAsync(
            SaveChangesCompletedEventData eventData,
            int result,
            CancellationToken cancellationToken = default)
        {
            if (!Called)
            {
                Called = true;
                await action();
            }

            return result;
        }
    }
}
