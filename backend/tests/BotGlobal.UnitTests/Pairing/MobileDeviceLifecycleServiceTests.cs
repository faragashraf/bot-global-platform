using BotGlobal.Pairing.Application;
using BotGlobal.Pairing.Domain;
using BotGlobal.Pairing.Infrastructure.Persistence;
using BotGlobal.Pairing.Security;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.UnitTests.Pairing;

public sealed class MobileDeviceLifecycleServiceTests
{
    [Fact]
    public async Task UnpairAsync_RevokesActiveDevice()
    {
        await using var db =
            CreateDatabase();

        var credentialService =
            new MobileDeviceCredentialService();

        var issued =
            credentialService.Generate();

        var device =
            new MobileDevice(
                Guid.NewGuid(),
                Guid.NewGuid(),
                "installation-1",
                "android",
                "Samsung",
                "1.0.1",
                issued.Hash,
                DateTimeOffset.UtcNow);

        db.Devices.Add(device);
        await db.SaveChangesAsync();

        var service =
            new MobileDeviceLifecycleService(
                db,
                credentialService,
                TimeProvider.System);

        var result =
            await service.UnpairAsync(
                issued.PlainText,
                CancellationToken.None);

        Assert.Equal(
            UnpairMobileDeviceOutcome.Unpaired,
            result);

        Assert.NotNull(device.RevokedAtUtc);
        Assert.False(device.IsActive);
    }

    [Fact]
    public async Task UnpairAsync_RejectsCredentialAfterRevocation()
    {
        await using var db =
            CreateDatabase();

        var credentialService =
            new MobileDeviceCredentialService();

        var issued =
            credentialService.Generate();

        var device =
            new MobileDevice(
                Guid.NewGuid(),
                Guid.NewGuid(),
                "installation-2",
                "android",
                "Samsung",
                "1.0.1",
                issued.Hash,
                DateTimeOffset.UtcNow);

        db.Devices.Add(device);
        await db.SaveChangesAsync();

        var service =
            new MobileDeviceLifecycleService(
                db,
                credentialService,
                TimeProvider.System);

        var first =
            await service.UnpairAsync(
                issued.PlainText,
                CancellationToken.None);

        var second =
            await service.UnpairAsync(
                issued.PlainText,
                CancellationToken.None);

        Assert.Equal(
            UnpairMobileDeviceOutcome.Unpaired,
            first);

        Assert.Equal(
            UnpairMobileDeviceOutcome.InvalidCredential,
            second);
    }

    [Fact]
    public async Task UnpairAsync_RejectsUnknownCredential()
    {
        await using var db =
            CreateDatabase();

        var credentialService =
            new MobileDeviceCredentialService();

        var service =
            new MobileDeviceLifecycleService(
                db,
                credentialService,
                TimeProvider.System);

        var result =
            await service.UnpairAsync(
                "not-a-valid-device-credential",
                CancellationToken.None);

        Assert.Equal(
            UnpairMobileDeviceOutcome.InvalidCredential,
            result);
    }

    private static PairingDbContext CreateDatabase()
    {
        var options =
            new DbContextOptionsBuilder<PairingDbContext>()
                .UseInMemoryDatabase(
                    $"pairing-{Guid.NewGuid():N}")
                .Options;

        return new PairingDbContext(options);
    }
}
