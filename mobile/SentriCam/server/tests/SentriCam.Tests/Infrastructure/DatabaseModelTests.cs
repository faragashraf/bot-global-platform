using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata;
using SentriCam.Application.Messaging;
using SentriCam.Domain.Devices;
using SentriCam.Domain.Hierarchy;
using SentriCam.Infrastructure.Persistence;
using SentriCam.Domain.Recordings;

namespace SentriCam.Tests.Infrastructure;

public sealed class DatabaseModelTests
{
    [Fact]
    public void DeviceModelContainsRequiredPlatformTables()
    {
        using var context = CreateContext();
        var tables = context.Model.GetEntityTypes()
            .Select(entity => entity.GetTableName())
            .Where(table => table is not null)
            .ToHashSet(StringComparer.Ordinal);

        Assert.Contains("Devices", tables);
        Assert.Contains("DeviceConnections", tables);
        Assert.Contains("DeviceSnapshots", tables);
        Assert.Contains("DeviceEvents", tables);
        Assert.Contains("DeviceCommands", tables);
        Assert.Contains("DeviceRegistrations", tables);
        Assert.Contains("DeviceCapabilities", tables);
        Assert.Contains("DeviceStatusHistory", tables);
        Assert.Contains("Organizations", tables);
        Assert.Contains("Sites", tables);
        Assert.Contains("Areas", tables);
        Assert.Contains("DeviceGroups", tables);
        Assert.Contains("OutboxMessages", tables);
    }

    [Fact]
    public void MutableDeviceRowsUseConcurrencyTokens()
    {
        using var context = CreateContext();

        AssertConcurrencyToken<Device>(context.Model);
        AssertConcurrencyToken<DeviceConnection>(context.Model);
        AssertConcurrencyToken<DeviceCommand>(context.Model);
        AssertConcurrencyToken<Organization>(context.Model);
        AssertConcurrencyToken<Site>(context.Model);
        AssertConcurrencyToken<Area>(context.Model);
        AssertConcurrencyToken<DeviceGroup>(context.Model);
        AssertConcurrencyToken<OutboxMessage>(context.Model);
    }

    [Fact]
    public void RetryAndSnapshotNaturalKeysAreUnique()
    {
        using var context = CreateContext();
        var commandType = context.Model.FindEntityType(typeof(DeviceCommand))!;
        var snapshotType = context.Model.FindEntityType(typeof(DeviceSnapshot))!;
        var outboxType = context.Model.FindEntityType(typeof(OutboxMessage))!;

        Assert.Contains(
            commandType.GetIndexes(),
            index => index.IsUnique
                && index.GetDatabaseName() == "UX_DeviceCommands_DeviceId_CorrelationId");
        Assert.Contains(
            snapshotType.GetIndexes(),
            index => index.IsUnique
                && index.GetDatabaseName() == "UX_DeviceSnapshots_DeviceId_SnapshotVersion");
        Assert.Contains(
            outboxType.GetIndexes(),
            index => index.IsUnique
                && index.GetDatabaseName() == "UX_OutboxMessages_EventId");
    }

    [Fact]
    public void DeviceIdentityAndActiveConnectionIndexesAreUnique()
    {
        using var context = CreateContext();
        var deviceType = context.Model.FindEntityType(typeof(Device))!;
        var identityType = deviceType.FindNavigation(nameof(Device.Identity))!.TargetEntityType;
        var connectionType = context.Model.FindEntityType(typeof(DeviceConnection))!;

        Assert.Contains(
            identityType.GetIndexes(),
            index => index.IsUnique && index.GetDatabaseName() == "UX_Devices_InstallationId");
        Assert.Contains(
            connectionType.GetIndexes(),
            index => index.IsUnique
                && index.GetDatabaseName() == "UX_DeviceConnections_OneActivePerDevice"
                && index.GetFilter() is not null);
    }

    [Fact]
    public void RecordingLibraryModelContainsThumbnailLifecycleAndQueryIndexes()
    {
        using var context = CreateContext();
        var recordingType = context.Model.FindEntityType(typeof(Recording))!;

        Assert.NotNull(recordingType.FindProperty(nameof(Recording.ThumbnailState)));
        Assert.NotNull(recordingType.FindProperty(nameof(Recording.ThumbnailGenerationAttempts)));
        Assert.NotNull(recordingType.FindProperty(nameof(Recording.ThumbnailErrorCode)));
        Assert.NotNull(recordingType.FindProperty(nameof(Recording.ThumbnailGeneratedUtc)));
        Assert.Contains(recordingType.GetIndexes(), index => index.GetDatabaseName() == "IX_Recordings_ThumbnailState_UploadedUtc");
        Assert.Contains(recordingType.GetIndexes(), index => index.GetDatabaseName() == "IX_Recordings_DurationMilliseconds_CreatedUtc");
        Assert.Contains(recordingType.GetIndexes(), index => index.GetDatabaseName() == "IX_Recordings_SizeBytes_CreatedUtc");
    }

    private static void AssertConcurrencyToken<TEntity>(IModel model)
        where TEntity : class
    {
        var entityType = model.FindEntityType(typeof(TEntity))!;
        var rowVersion = entityType.FindProperty("RowVersion")!;
        Assert.True(rowVersion.IsConcurrencyToken);
        Assert.False(rowVersion.IsNullable);
        Assert.Equal(ValueGenerated.OnAddOrUpdate, rowVersion.ValueGenerated);
    }

    private static SentriCamDbContext CreateContext()
    {
        var options = new DbContextOptionsBuilder<SentriCamDbContext>()
            .UseInMemoryDatabase(Guid.NewGuid().ToString("N"))
            .Options;
        return new SentriCamDbContext(options);
    }
}
