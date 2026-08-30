using BotGlobal.Notifications;
using BotGlobal.Notifications.Domain;
using BotGlobal.Notifications.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;

namespace BotGlobal.UnitTests.Notifications;

public sealed class NotificationDeliveryOutboxMigrationTests
{
    private const string Initial =
        "20260821164303_InitialNotificationCampaigns";
    private const string Outbox =
        "20260830111844_AddNotificationDeliveryOutbox";

    [Fact]
    public void Forward_sql_backfills_a_deterministic_application_campaign_device_key()
    {
        var sql = ForwardSql();

        Assert.Contains(
            "LOWER(REPLACE(CONVERT(varchar(36), campaign.[PlatformClientId]), '-', ''))",
            sql,
            StringComparison.Ordinal);
        Assert.Contains(
            "+ ':' + LOWER(REPLACE(CONVERT(varchar(36), recipient.[CampaignId]), '-', ''))",
            sql,
            StringComparison.Ordinal);
        Assert.Contains(
            "+ ':' + LOWER(REPLACE(CONVERT(varchar(36), recipient.[MobileDeviceId]), '-', ''))",
            sql,
            StringComparison.Ordinal);
        Assert.Contains(
            "CREATE UNIQUE INDEX [UX_NotificationRecipients_DeliveryKey]",
            sql,
            StringComparison.Ordinal);

        var applicationId = Guid.Parse(
            "11111111-1111-1111-1111-111111111111");
        var campaignId = Guid.Parse(
            "22222222-2222-2222-2222-222222222222");
        var deviceId = Guid.Parse(
            "33333333-3333-3333-3333-333333333333");
        Assert.Equal(
            "11111111111111111111111111111111:22222222222222222222222222222222:33333333333333333333333333333333",
            NotificationRecipient.CreateDeliveryKey(
                applicationId,
                campaignId,
                deviceId));
    }

    [Theory]
    [InlineData(3)]
    [InlineData(4)]
    [InlineData(5)]
    [InlineData(2)]
    [InlineData(6)]
    public void Forward_sql_normalizes_zero_attempt_historical_terminal_rows(
        int historicalStatus)
    {
        var sql = ForwardSql();

        Assert.Contains(
            "WHEN [AttemptCount] < 1 THEN 1",
            sql,
            StringComparison.Ordinal);
        Assert.Contains(
            "WHERE [Status] IN (2, 3, 4, 5, 6)",
            sql,
            StringComparison.Ordinal);
        Assert.DoesNotContain(
            "[AttemptCount] > 0\n                    AND [Status] IN (2, 3, 4, 5, 6)",
            sql,
            StringComparison.Ordinal);
        Assert.Contains(
            $"WHEN {historicalStatus} THEN {HistoricalAttemptStatus(historicalStatus)}",
            sql,
            StringComparison.Ordinal);
        Assert.Contains(
            "recipient.[CurrentAttemptId]",
            sql,
            StringComparison.Ordinal);
        Assert.Contains(
            "recipient.[AttemptCount]",
            sql,
            StringComparison.Ordinal);
    }

    [Fact]
    public void Forward_sql_leaves_pending_and_expired_rows_without_a_synthetic_attempt()
    {
        var sql = ForwardSql();
        var backfillStart = sql.IndexOf(
            "UPDATE [notifications].[NotificationRecipients]",
            sql.IndexOf("CREATE TABLE [notifications].[NotificationDeliveryAttempts]", StringComparison.Ordinal),
            StringComparison.Ordinal);
        var backfillEnd = sql.IndexOf(
            "CREATE UNIQUE INDEX [UX_NotificationRecipients_CurrentAttempt]",
            backfillStart,
            StringComparison.Ordinal);
        var backfill = sql[backfillStart..backfillEnd];

        Assert.Contains(
            "WHERE [Status] IN (2, 3, 4, 5, 6)",
            backfill,
            StringComparison.Ordinal);
        Assert.DoesNotContain(
            "[Status] IN (1, 2, 3, 4, 5, 6, 7)",
            backfill,
            StringComparison.Ordinal);
        Assert.Contains(
            "[Status] IN (1, 2, 7, 10) OR [CurrentAttemptId] IS NOT NULL",
            sql,
            StringComparison.Ordinal);
    }

    [Theory]
    [InlineData(8, 2, 5)]
    [InlineData(8, 3, 3)]
    [InlineData(8, 4, 4)]
    [InlineData(8, 5, 2)]
    [InlineData(8, 6, 5)]
    [InlineData(8, 7, 6)]
    [InlineData(8, 8, 5)]
    [InlineData(8, 9, 7)]
    [InlineData(9, 8, 5)]
    [InlineData(10, 10, 6)]
    public void Rollback_sql_maps_new_recipient_states_into_the_old_domain(
        int recipientStatus,
        int attemptStatus,
        int expectedOldStatus)
    {
        var sql = ReverseSql();

        Assert.Contains(
            "WHERE recipient.[Status] IN (8, 9, 10)",
            sql,
            StringComparison.Ordinal);
        Assert.Contains(
            "WHEN recipient.[Status] = 10 THEN 6",
            sql,
            StringComparison.Ordinal);
        Assert.Contains(
            "WHEN recipient.[Status] IN (8, 9)",
            sql,
            StringComparison.Ordinal);
        Assert.Equal(
            expectedOldStatus,
            ExpectedDowngradedStatus(recipientStatus, attemptStatus));
    }

    [Theory]
    [InlineData(1)]
    [InlineData(2)]
    [InlineData(3)]
    [InlineData(4)]
    [InlineData(5)]
    [InlineData(6)]
    [InlineData(7)]
    public void Rollback_preserves_old_recipient_states(int oldStatus)
    {
        Assert.Equal(oldStatus, ExpectedDowngradedStatus(oldStatus, null));
    }

    [Fact]
    public void Rollback_sql_maps_cancelled_campaigns_before_restoring_old_constraints()
    {
        var sql = ReverseSql();
        var map = sql.IndexOf(
            "WHERE [Status] = 8",
            StringComparison.Ordinal);
        var oldCampaignConstraint = sql.LastIndexOf(
            "CHECK ([Status] BETWEEN 1 AND 7)",
            StringComparison.Ordinal);
        var oldRecipientConstraint = sql.IndexOf(
            "CK_NotificationRecipients_Status",
            map,
            StringComparison.Ordinal);

        Assert.True(map >= 0);
        Assert.True(oldCampaignConstraint > map);
        Assert.True(oldRecipientConstraint > map);
        Assert.Contains(
            "SET [Status] = 5",
            sql,
            StringComparison.Ordinal);
        Assert.Contains(
            "COALESCE([CompletedAtUtc], SYSDATETIMEOFFSET())",
            sql,
            StringComparison.Ordinal);
    }

    [Fact]
    public void Sql_server_generates_forward_and_reverse_scripts_once_without_provider_errors()
    {
        var forward = ForwardSql();
        var reverse = ReverseSql();

        Assert.Contains(
            "CREATE TABLE [notifications].[NotificationDeliveryAttempts]",
            forward,
            StringComparison.Ordinal);
        Assert.Contains(
            "DROP TABLE [notifications].[NotificationDeliveryAttempts]",
            reverse,
            StringComparison.Ordinal);
        Assert.DoesNotContain("GO\nGO", forward, StringComparison.Ordinal);
        Assert.DoesNotContain("GO\nGO", reverse, StringComparison.Ordinal);
    }

    private static string ForwardSql() => Migrator().GenerateScript(
        Initial,
        Outbox);

    private static string ReverseSql() => Migrator().GenerateScript(
        Outbox,
        Initial);

    private static IMigrator Migrator()
    {
        var options = new DbContextOptionsBuilder<NotificationsDbContext>()
            .UseSqlServer(
                "Server=localhost;Database=NotificationsMigrationTests;Trusted_Connection=True;TrustServerCertificate=True",
                sql => sql.MigrationsHistoryTable(
                    NotificationsModule.MigrationsHistoryTableName,
                    NotificationsModule.DatabaseSchema))
            .Options;
        var context = new NotificationsDbContext(options);
        return context.GetService<IMigrator>();
    }

    private static int HistoricalAttemptStatus(int recipientStatus) =>
        recipientStatus switch
        {
            2 => 5,
            3 => 3,
            4 => 4,
            5 => 6,
            6 => 7,
            _ => throw new ArgumentOutOfRangeException(
                nameof(recipientStatus))
        };

    private static int ExpectedDowngradedStatus(
        int recipientStatus,
        int? attemptStatus)
    {
        if (recipientStatus == 10)
        {
            return 6;
        }

        return attemptStatus switch
        {
            3 => 3,
            4 => 4,
            5 => 2,
            6 => 5,
            7 => 6,
            9 => 7,
            10 => 6,
            2 or 8 => 5,
            1 => 1,
            _ when recipientStatus is 8 or 9 => 5,
            _ => recipientStatus
        };
    }
}
