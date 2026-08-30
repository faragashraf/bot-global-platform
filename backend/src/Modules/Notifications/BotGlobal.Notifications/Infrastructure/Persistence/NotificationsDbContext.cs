using BotGlobal.Notifications.Domain;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Notifications.Infrastructure.Persistence;

public sealed class NotificationsDbContext(
    DbContextOptions<NotificationsDbContext> options)
    : DbContext(options)
{
    public DbSet<NotificationCampaign> Campaigns =>
        Set<NotificationCampaign>();

    public DbSet<NotificationRecipient> Recipients =>
        Set<NotificationRecipient>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.HasDefaultSchema(
            NotificationsModule.DatabaseSchema);

        modelBuilder.ApplyConfigurationsFromAssembly(
            typeof(NotificationsDbContext).Assembly);

        base.OnModelCreating(modelBuilder);
    }
}
