using BotGlobal.Notifications.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata;
using System.Security.Cryptography;

namespace BotGlobal.Notifications.Infrastructure.Persistence;

public sealed class NotificationsDbContext(
    DbContextOptions<NotificationsDbContext> options)
    : DbContext(options)
{
    public DbSet<NotificationCampaign> Campaigns =>
        Set<NotificationCampaign>();

    public DbSet<NotificationRecipient> Recipients =>
        Set<NotificationRecipient>();

    public DbSet<NotificationDeliveryAttempt> DeliveryAttempts =>
        Set<NotificationDeliveryAttempt>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.HasDefaultSchema(
            NotificationsModule.DatabaseSchema);

        modelBuilder.ApplyConfigurationsFromAssembly(
            typeof(NotificationsDbContext).Assembly);

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
        if (!Database.IsNpgsql())
        {
            return;
        }

        foreach (var entityType in modelBuilder.Model.GetEntityTypes())
        {
            var rowVersion = entityType.FindProperty("RowVersion");
            if (rowVersion is null)
            {
                continue;
            }

            rowVersion.ValueGenerated = ValueGenerated.Never;
            rowVersion.SetBeforeSaveBehavior(PropertySaveBehavior.Save);
            rowVersion.SetAfterSaveBehavior(PropertySaveBehavior.Save);
        }
    }

    private void StampPortableRowVersions()
    {
        if (!Database.IsNpgsql())
        {
            return;
        }

        foreach (var entry in ChangeTracker.Entries()
            .Where(entry => entry.State is EntityState.Added or EntityState.Modified))
        {
            if (entry.Metadata.FindProperty("RowVersion") is null)
            {
                continue;
            }

            entry.Property("RowVersion").CurrentValue =
                RandomNumberGenerator.GetBytes(8);
        }
    }
}
