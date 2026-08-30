using BotGlobal.Notifications;
using BotGlobal.Notifications.Domain;
using BotGlobal.Notifications.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;

namespace BotGlobal.UnitTests.Notifications;

public sealed class NotificationsPersistenceModelTests
{
    private readonly NotificationsDbContext _context = CreateContext();

    [Fact]
    public void Model_contains_only_two_notifications_schema_tables()
    {
        var tables = _context.Model.GetEntityTypes()
            .Select(entity => (entity.GetSchema(), entity.GetTableName()))
            .OrderBy(table => table.Item2)
            .ToArray();

        Assert.Equal(2, tables.Length);
        Assert.All(tables, table =>
            Assert.Equal(NotificationsModule.DatabaseSchema, table.Item1));
        Assert.Equal(
            ["NotificationCampaigns", "NotificationRecipients"],
            tables.Select(table => table.Item2!).ToArray());
    }

    [Fact]
    public void Only_recipient_to_campaign_foreign_key_exists()
    {
        var foreignKeys = _context.Model.GetEntityTypes()
            .SelectMany(entity => entity.GetForeignKeys())
            .ToArray();

        var foreignKey = Assert.Single(foreignKeys);
        Assert.Equal(typeof(NotificationRecipient), foreignKey.DeclaringEntityType.ClrType);
        Assert.Equal(typeof(NotificationCampaign), foreignKey.PrincipalEntityType.ClrType);
    }

    [Fact]
    public void Required_unique_and_dispatch_indexes_are_configured()
    {
        var campaign = _context.Model.FindEntityType(typeof(NotificationCampaign))!;
        var recipient = _context.Model.FindEntityType(typeof(NotificationRecipient))!;

        Assert.True(campaign.GetIndexes().Single(index =>
            index.GetDatabaseName() == "UX_NotificationCampaigns_Admin_IdempotencyKey").IsUnique);
        Assert.NotNull(campaign.GetIndexes().SingleOrDefault(index =>
            index.GetDatabaseName() == "IX_NotificationCampaigns_PlatformClient_CreatedAtUtc"));
        Assert.True(recipient.GetIndexes().Single(index =>
            index.GetDatabaseName() == "UX_NotificationRecipients_Campaign_Device").IsUnique);
        Assert.NotNull(recipient.GetIndexes().SingleOrDefault(index =>
            index.GetDatabaseName() == "IX_NotificationRecipients_DispatchWork"));
    }

    [Fact]
    public void Both_aggregates_use_row_version_concurrency()
    {
        foreach (var type in new[]
                 {
                     typeof(NotificationCampaign),
                     typeof(NotificationRecipient)
                 })
        {
            var rowVersion = _context.Model.FindEntityType(type)!
                .FindProperty("RowVersion")!;

            Assert.True(rowVersion.IsConcurrencyToken);
            Assert.Equal(Microsoft.EntityFrameworkCore.Metadata.ValueGenerated.OnAddOrUpdate, rowVersion.ValueGenerated);
        }
    }

    [Fact]
    public void Persistence_has_no_token_or_external_subject_columns()
    {
        var propertyNames = _context.Model.GetEntityTypes()
            .SelectMany(entity => entity.GetProperties())
            .Select(property => property.Name)
            .ToArray();

        Assert.DoesNotContain(propertyNames, property =>
            property.Contains("RegistrationToken", StringComparison.OrdinalIgnoreCase));
        Assert.DoesNotContain(propertyNames, property =>
            property.Contains("ExternalSubject", StringComparison.OrdinalIgnoreCase));
        Assert.DoesNotContain(propertyNames, property =>
            property.Contains("Credential", StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public void Runtime_options_use_independent_migration_history()
    {
        var options = _context.GetService<IDbContextOptions>();
        var extension = options.Extensions.Single(candidate =>
            candidate.GetType().Name.Contains("SqlServerOptionsExtension", StringComparison.Ordinal));

        Assert.Equal(
            NotificationsModule.DatabaseSchema,
            extension.GetType().GetProperty("MigrationsHistoryTableSchema")?.GetValue(extension));
        Assert.Equal(
            NotificationsModule.MigrationsHistoryTableName,
            extension.GetType().GetProperty("MigrationsHistoryTableName")?.GetValue(extension));
    }

    private static NotificationsDbContext CreateContext()
    {
        var options = new DbContextOptionsBuilder<NotificationsDbContext>()
            .UseSqlServer(
                "Server=localhost;Database=NotificationsModelTests;Trusted_Connection=True;TrustServerCertificate=True",
                sql => sql.MigrationsHistoryTable(
                    NotificationsModule.MigrationsHistoryTableName,
                    NotificationsModule.DatabaseSchema))
            .Options;

        return new NotificationsDbContext(options);
    }
}
