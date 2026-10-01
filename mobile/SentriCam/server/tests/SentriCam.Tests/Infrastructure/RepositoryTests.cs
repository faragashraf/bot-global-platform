using Microsoft.EntityFrameworkCore;
using SentriCam.Domain.Devices;
using SentriCam.Infrastructure.Persistence;
using SentriCam.Infrastructure.Persistence.Repositories;
using SentriCam.Tests.TestDoubles;

namespace SentriCam.Tests.Infrastructure;

public sealed class RepositoryTests
{
    [Fact]
    public async Task DeviceRepositoryFindsDeviceByInstallationIdWithCapabilities()
    {
        var options = CreateOptions();
        await using (var writeContext = new SentriCamDbContext(options))
        {
            var device = DeviceTestFactory.CreateDevice();
            var repository = new DeviceRepository(writeContext);
            repository.Add(device);
            SetRowVersion(writeContext, device);
            await writeContext.SaveChangesAsync(TestContext.Current.CancellationToken);
        }

        await using var readContext = new SentriCamDbContext(options);
        var readRepository = new DeviceRepository(readContext);

        var loaded = await readRepository.GetByInstallationIdAsync(
            "installation-001",
            TestContext.Current.CancellationToken);

        Assert.NotNull(loaded);
        Assert.Equal("Front Door", loaded.DisplayName);
        Assert.Equal("CAMERA", Assert.Single(loaded.Capabilities).NormalizedName);
    }

    [Fact]
    public async Task CommandRepositoryRoundTripsQueuedCommand()
    {
        var options = CreateOptions();
        var device = DeviceTestFactory.CreateDevice();
        var command = DeviceCommand.Queue(
            device.Id,
            DeviceCommandKind.StartMonitoring,
            "correlation-1",
            DeviceTestFactory.Now);

        await using (var writeContext = new SentriCamDbContext(options))
        {
            writeContext.Devices.Add(device);
            SetRowVersion(writeContext, device);
            var repository = new DeviceCommandRepository(writeContext);
            repository.Add(command);
            writeContext.Entry(command).Property<byte[]>("RowVersion").CurrentValue = [1];
            await writeContext.SaveChangesAsync(TestContext.Current.CancellationToken);
        }

        await using var readContext = new SentriCamDbContext(options);
        var readRepository = new DeviceCommandRepository(readContext);

        var loaded = await readRepository.GetByIdAsync(
            command.Id,
            TestContext.Current.CancellationToken);

        Assert.NotNull(loaded);
        Assert.Equal(DeviceCommandState.Pending, loaded.State);
        Assert.Equal("correlation-1", loaded.CorrelationId);
    }

    [Fact]
    public async Task StaleDeviceUpdateTriggersOptimisticConcurrencyFailure()
    {
        var options = CreateOptions();
        await using (var seedContext = new SentriCamDbContext(options))
        {
            var device = DeviceTestFactory.CreateDevice();
            seedContext.Devices.Add(device);
            SetRowVersion(seedContext, device);
            await seedContext.SaveChangesAsync(TestContext.Current.CancellationToken);
        }

        await using var firstContext = new SentriCamDbContext(options);
        await using var staleContext = new SentriCamDbContext(options);
        var first = await firstContext.Devices.SingleAsync(TestContext.Current.CancellationToken);
        var stale = await staleContext.Devices.SingleAsync(TestContext.Current.CancellationToken);

        first.MarkSeen(DeviceTestFactory.Now.AddMinutes(1));
        firstContext.Entry(first).Property<byte[]>("RowVersion").CurrentValue = [2];
        await firstContext.SaveChangesAsync(TestContext.Current.CancellationToken);

        stale.MarkSeen(DeviceTestFactory.Now.AddMinutes(2));
        staleContext.Entry(stale).Property<byte[]>("RowVersion").CurrentValue = [3];

        await Assert.ThrowsAsync<DbUpdateConcurrencyException>(() =>
            staleContext.SaveChangesAsync(TestContext.Current.CancellationToken));
    }

    private static DbContextOptions<SentriCamDbContext> CreateOptions() =>
        new DbContextOptionsBuilder<SentriCamDbContext>()
            .UseInMemoryDatabase(Guid.NewGuid().ToString("N"))
            .Options;

    private static void SetRowVersion(SentriCamDbContext context, Device device) =>
        context.Entry(device).Property<byte[]>("RowVersion").CurrentValue = [1];
}
