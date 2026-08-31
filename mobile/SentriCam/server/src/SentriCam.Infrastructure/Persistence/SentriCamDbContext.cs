using Microsoft.EntityFrameworkCore;
using SentriCam.Application.Abstractions.Persistence;
using SentriCam.Application.Messaging;
using SentriCam.Domain.Common.Events;
using SentriCam.Domain.Devices;
using SentriCam.Domain.Hierarchy;
using SentriCam.Domain.Recordings;
using Microsoft.EntityFrameworkCore.Metadata;
using Microsoft.EntityFrameworkCore.Storage.ValueConversion;
using System.Security.Cryptography;
using System.Reflection;

namespace SentriCam.Infrastructure.Persistence;

public sealed class SentriCamDbContext(DbContextOptions<SentriCamDbContext> options)
    : DbContext(options), IUnitOfWork
{
    private static readonly ValueConverter<DateTimeOffset, DateTime> SqliteDateTimeOffsetConverter = new(
        value => value.UtcDateTime,
        value => new DateTimeOffset(DateTime.SpecifyKind(value, DateTimeKind.Utc)));

    public DbSet<Device> Devices => Set<Device>();

    public DbSet<DeviceRegistration> DeviceRegistrations => Set<DeviceRegistration>();

    public DbSet<DeviceCapability> DeviceCapabilities => Set<DeviceCapability>();

    public DbSet<DeviceConnection> DeviceConnections => Set<DeviceConnection>();

    public DbSet<DeviceSnapshot> DeviceSnapshots => Set<DeviceSnapshot>();

    public DbSet<DeviceCommand> DeviceCommands => Set<DeviceCommand>();

    public DbSet<DeviceEvent> DeviceEvents => Set<DeviceEvent>();

    public DbSet<DeviceStatusHistory> DeviceStatusHistory => Set<DeviceStatusHistory>();

    public DbSet<Recording> Recordings => Set<Recording>();

    public DbSet<Organization> Organizations => Set<Organization>();

    public DbSet<Site> Sites => Set<Site>();

    public DbSet<Area> Areas => Set<Area>();

    public DbSet<DeviceGroup> DeviceGroups => Set<DeviceGroup>();

    public DbSet<OutboxMessage> OutboxMessages => Set<OutboxMessage>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        ArgumentNullException.ThrowIfNull(modelBuilder);
        modelBuilder.Ignore<DomainEvent>();
        modelBuilder.ApplyConfigurationsFromAssembly(typeof(SentriCamDbContext).Assembly);
        ConfigurePortableProviderModel(modelBuilder);
        base.OnModelCreating(modelBuilder);
    }

    public override int SaveChanges(bool acceptAllChangesOnSuccess)
    {
        StampPortableRowVersions();
        return base.SaveChanges(acceptAllChangesOnSuccess);
    }

    public override Task<int> SaveChangesAsync(
        bool acceptAllChangesOnSuccess,
        CancellationToken cancellationToken = default)
    {
        StampPortableRowVersions();
        return base.SaveChangesAsync(acceptAllChangesOnSuccess, cancellationToken);
    }

    private void ConfigurePortableProviderModel(ModelBuilder modelBuilder)
    {
        if (!Database.IsSqlite() && !Database.IsNpgsql())
        {
            return;
        }

        if (Database.IsSqlite())
        {
            var strftime = typeof(SqliteDbFunctions).GetMethod(
                nameof(SqliteDbFunctions.Strftime),
                BindingFlags.Public | BindingFlags.Static)
                ?? throw new InvalidOperationException("SQLite strftime mapping could not be found.");
            modelBuilder
                .HasDbFunction(strftime)
                .HasName("strftime")
                .IsBuiltIn();
        }

        foreach (var entityType in modelBuilder.Model.GetEntityTypes())
        {
            if (Database.IsSqlite())
            {
                foreach (var dateTimeOffset in entityType.GetProperties()
                    .Where(property => property.ClrType == typeof(DateTimeOffset)
                        || property.ClrType == typeof(DateTimeOffset?)))
                {
                    dateTimeOffset.SetValueConverter(SqliteDateTimeOffsetConverter);
                }
            }

            foreach (var property in entityType.GetProperties()
                .Where(property => property.GetColumnType() == "nvarchar(max)"))
            {
                property.SetColumnType(Database.IsNpgsql() ? "text" : "TEXT");
            }

            var rowVersion = entityType.FindProperty("RowVersion");
            if (rowVersion is null)
            {
                continue;
            }
            rowVersion.ValueGenerated = ValueGenerated.Never;
            rowVersion.SetBeforeSaveBehavior(PropertySaveBehavior.Save);
            rowVersion.SetAfterSaveBehavior(PropertySaveBehavior.Save);
        }

        var activeConnectionIndex = modelBuilder.Model.FindEntityType(typeof(DeviceConnection))?
            .GetIndexes()
            .SingleOrDefault(index => index.GetDatabaseName() == "UX_DeviceConnections_OneActivePerDevice");
        activeConnectionIndex?.SetFilter(
            Database.IsNpgsql() ? "\"IsActive\" = TRUE" : "\"IsActive\" = 1");
    }

    private void StampPortableRowVersions()
    {
        if (!Database.IsSqlite() && !Database.IsNpgsql())
        {
            return;
        }
        foreach (var entry in ChangeTracker.Entries()
            .Where(entry => entry.State is EntityState.Added or EntityState.Modified))
        {
            var property = entry.Metadata.FindProperty("RowVersion");
            if (property is null)
            {
                continue;
            }
            entry.Property("RowVersion").CurrentValue = RandomNumberGenerator.GetBytes(8);
        }
    }
}
