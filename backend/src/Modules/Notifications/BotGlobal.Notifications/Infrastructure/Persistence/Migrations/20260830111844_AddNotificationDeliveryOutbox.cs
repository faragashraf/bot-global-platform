using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace BotGlobal.Notifications.Infrastructure.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class AddNotificationDeliveryOutbox : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            // EF owns the transaction. Generated scripts also need to abort it
            // on a backfill/constraint error rather than continue to history.
            migrationBuilder.Sql("SET XACT_ABORT ON;");

            migrationBuilder.DropCheckConstraint(
                name: "CK_NotificationCampaigns_Status",
                schema: "notifications",
                table: "NotificationCampaigns");

            migrationBuilder.DropCheckConstraint(
                name: "CK_NotificationRecipients_NextAttempt",
                schema: "notifications",
                table: "NotificationRecipients");

            migrationBuilder.DropCheckConstraint(
                name: "CK_NotificationRecipients_Status",
                schema: "notifications",
                table: "NotificationRecipients");

            migrationBuilder.AddColumn<Guid>(
                name: "CurrentAttemptId",
                schema: "notifications",
                table: "NotificationRecipients",
                type: "uniqueidentifier",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "DeliveryKey",
                schema: "notifications",
                table: "NotificationRecipients",
                type: "varchar(100)",
                unicode: false,
                maxLength: 100,
                nullable: true);

            migrationBuilder.Sql(DeferCompilation(
                """
                UPDATE recipient
                SET [DeliveryKey] =
                    LOWER(REPLACE(CONVERT(varchar(36), campaign.[PlatformClientId]), '-', ''))
                    + ':' + LOWER(REPLACE(CONVERT(varchar(36), recipient.[CampaignId]), '-', ''))
                    + ':' + LOWER(REPLACE(CONVERT(varchar(36), recipient.[MobileDeviceId]), '-', ''))
                FROM [notifications].[NotificationRecipients] AS recipient
                INNER JOIN [notifications].[NotificationCampaigns] AS campaign
                    ON campaign.[Id] = recipient.[CampaignId];
                """));

            migrationBuilder.AlterColumn<string>(
                name: "DeliveryKey",
                schema: "notifications",
                table: "NotificationRecipients",
                type: "varchar(100)",
                unicode: false,
                maxLength: 100,
                nullable: false,
                oldClrType: typeof(string),
                oldType: "varchar(100)",
                oldUnicode: false,
                oldMaxLength: 100,
                oldNullable: true);

            migrationBuilder.CreateTable(
                name: "NotificationDeliveryAttempts",
                schema: "notifications",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    NotificationRecipientId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    ApplicationId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    CampaignId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    MobileDeviceId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    DeliveryKey = table.Column<string>(type: "varchar(100)", unicode: false, maxLength: 100, nullable: false),
                    AttemptNumber = table.Column<int>(type: "int", nullable: false),
                    LeaseId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    Status = table.Column<int>(type: "int", nullable: false),
                    CreatedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    ProviderInvocationStartedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    CompletedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    Transport = table.Column<string>(type: "varchar(32)", unicode: false, maxLength: 32, nullable: true),
                    ProviderMessageId = table.Column<string>(type: "varchar(500)", unicode: false, maxLength: 500, nullable: true),
                    SafeErrorCode = table.Column<string>(type: "varchar(100)", unicode: false, maxLength: 100, nullable: true),
                    RowVersion = table.Column<byte[]>(type: "rowversion", rowVersion: true, nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_NotificationDeliveryAttempts", x => x.Id);
                    table.CheckConstraint("CK_NotificationDeliveryAttempts_AttemptNumber", "[AttemptNumber] >= 1");
                    table.CheckConstraint("CK_NotificationDeliveryAttempts_Completion", "([Status] IN (1, 2) AND [CompletedAtUtc] IS NULL) OR ([Status] BETWEEN 3 AND 10 AND [CompletedAtUtc] IS NOT NULL)");
                    table.CheckConstraint("CK_NotificationDeliveryAttempts_Invocation", "([Status] IN (1, 9, 10) AND [ProviderInvocationStartedAtUtc] IS NULL) OR ([Status] BETWEEN 2 AND 8 AND [ProviderInvocationStartedAtUtc] IS NOT NULL)");
                    table.CheckConstraint("CK_NotificationDeliveryAttempts_Status", "[Status] BETWEEN 1 AND 10");
                    table.ForeignKey(
                        name: "FK_NotificationDeliveryAttempts_NotificationRecipients_NotificationRecipientId",
                        column: x => x.NotificationRecipientId,
                        principalSchema: "notifications",
                        principalTable: "NotificationRecipients",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            // Reconstruct one latest-known result, not the complete attempt
            // history. The new IDs are synthetic correlation identifiers;
            // they do not recover historical lease ownership or provider IDs.
            migrationBuilder.Sql(DeferCompilation(
                """
                UPDATE [notifications].[NotificationRecipients]
                SET [AttemptCount] = CASE
                        WHEN [AttemptCount] < 1 THEN 1
                        ELSE [AttemptCount]
                    END,
                    [CurrentAttemptId] = NEWID()
                WHERE [Status] IN (2, 3, 4, 5, 6)
                    AND [CurrentAttemptId] IS NULL;

                INSERT INTO [notifications].[NotificationDeliveryAttempts]
                (
                    [Id], [NotificationRecipientId], [ApplicationId], [CampaignId],
                    [MobileDeviceId], [DeliveryKey], [AttemptNumber], [LeaseId],
                    [Status], [CreatedAtUtc], [ProviderInvocationStartedAtUtc],
                    [CompletedAtUtc], [Transport], [ProviderMessageId],
                    [SafeErrorCode]
                )
                SELECT
                    recipient.[CurrentAttemptId],
                    recipient.[Id],
                    campaign.[PlatformClientId],
                    recipient.[CampaignId],
                    recipient.[MobileDeviceId],
                    recipient.[DeliveryKey],
                    recipient.[AttemptCount],
                    NEWID(),
                    CASE recipient.[Status]
                        WHEN 2 THEN 5
                        WHEN 3 THEN 3
                        WHEN 4 THEN 4
                        WHEN 5 THEN 6
                        WHEN 6 THEN 7
                    END,
                    COALESCE(recipient.[LastAttemptAtUtc], campaign.[CreatedAtUtc]),
                    COALESCE(recipient.[LastAttemptAtUtc], campaign.[CreatedAtUtc]),
                    COALESCE(recipient.[LastAttemptAtUtc], recipient.[DispatchedAtUtc], campaign.[CreatedAtUtc]),
                    recipient.[LastTransport],
                    NULL,
                    recipient.[LastSafeErrorCode]
                FROM [notifications].[NotificationRecipients] AS recipient
                INNER JOIN [notifications].[NotificationCampaigns] AS campaign
                    ON campaign.[Id] = recipient.[CampaignId]
                WHERE recipient.[CurrentAttemptId] IS NOT NULL
                    AND recipient.[Status] IN (2, 3, 4, 5, 6);
                """));

            migrationBuilder.AddCheckConstraint(
                name: "CK_NotificationCampaigns_Status",
                schema: "notifications",
                table: "NotificationCampaigns",
                sql: "[Status] BETWEEN 1 AND 8");

            // The filtered predicate also binds the newly introduced column.
            migrationBuilder.Sql(DeferCompilation(
                """
                CREATE UNIQUE INDEX [UX_NotificationRecipients_CurrentAttempt]
                ON [notifications].[NotificationRecipients] ([CurrentAttemptId])
                WHERE [CurrentAttemptId] IS NOT NULL;
                """));

            migrationBuilder.CreateIndex(
                name: "UX_NotificationRecipients_DeliveryKey",
                schema: "notifications",
                table: "NotificationRecipients",
                column: "DeliveryKey",
                unique: true);

            migrationBuilder.AddCheckConstraint(
                name: "CK_NotificationRecipients_CurrentAttempt",
                schema: "notifications",
                table: "NotificationRecipients",
                sql: "[Status] IN (1, 2, 7, 10) OR [CurrentAttemptId] IS NOT NULL");

            migrationBuilder.AddCheckConstraint(
                name: "CK_NotificationRecipients_NextAttempt",
                schema: "notifications",
                table: "NotificationRecipients",
                sql: "([Status] IN (1, 2) AND [NextAttemptAtUtc] IS NOT NULL) OR ([Status] BETWEEN 3 AND 10 AND [NextAttemptAtUtc] IS NULL)");

            migrationBuilder.AddCheckConstraint(
                name: "CK_NotificationRecipients_Status",
                schema: "notifications",
                table: "NotificationRecipients",
                sql: "[Status] BETWEEN 1 AND 10");

            migrationBuilder.CreateIndex(
                name: "IX_NotificationDeliveryAttempts_Application_Campaign_Delivery",
                schema: "notifications",
                table: "NotificationDeliveryAttempts",
                columns: new[] { "ApplicationId", "CampaignId", "DeliveryKey" });

            migrationBuilder.CreateIndex(
                name: "IX_NotificationDeliveryAttempts_Recovery",
                schema: "notifications",
                table: "NotificationDeliveryAttempts",
                columns: new[] { "Status", "CompletedAtUtc" });

            migrationBuilder.CreateIndex(
                name: "UX_NotificationDeliveryAttempts_Recipient_Number",
                schema: "notifications",
                table: "NotificationDeliveryAttempts",
                columns: new[] { "NotificationRecipientId", "AttemptNumber" },
                unique: true);
        }

        // GenerateScript can combine EF commands into one SQL Server batch.
        // Constant dynamic SQL binds dependent expressions only after the
        // preceding DDL has run, while retaining EF's ambient transaction.
        private static string DeferCompilation(string sql) =>
            $"EXEC(N'{sql.Replace("'", "''", StringComparison.Ordinal)}');";

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropCheckConstraint(
                name: "CK_NotificationCampaigns_Status",
                schema: "notifications",
                table: "NotificationCampaigns");

            migrationBuilder.DropCheckConstraint(
                name: "CK_NotificationRecipients_CurrentAttempt",
                schema: "notifications",
                table: "NotificationRecipients");

            migrationBuilder.DropCheckConstraint(
                name: "CK_NotificationRecipients_NextAttempt",
                schema: "notifications",
                table: "NotificationRecipients");

            migrationBuilder.DropCheckConstraint(
                name: "CK_NotificationRecipients_Status",
                schema: "notifications",
                table: "NotificationRecipients");

            // The old schema cannot represent Sending, Ambiguous, or Cancelled.
            // Reconcile Sending from its durable attempt where possible. An
            // unresolved invocation and an explicitly Ambiguous outcome map to
            // FailedPermanent, the safest old terminal state: rollback must not
            // turn an uncertain provider acceptance into an automatic resend.
            // Cancelled maps to the old non-delivery terminal SkippedRevoked.
            // A cancelled campaign maps to CompletedWithFailures, the closest
            // old terminal campaign state, so rollback cannot requeue it.
            migrationBuilder.Sql(
                """
                UPDATE recipient
                SET [Status] = CASE
                        WHEN recipient.[Status] = 10 THEN 6
                        WHEN attempt.[Status] = 3 THEN 3
                        WHEN attempt.[Status] = 4 THEN 4
                        WHEN attempt.[Status] = 5 THEN 2
                        WHEN attempt.[Status] = 6 THEN 5
                        WHEN attempt.[Status] = 7 THEN 6
                        WHEN attempt.[Status] = 9 THEN 7
                        WHEN attempt.[Status] = 10 THEN 6
                        WHEN recipient.[Status] IN (8, 9)
                            OR attempt.[Status] IN (2, 8) THEN 5
                        WHEN attempt.[Status] = 1 THEN 1
                        ELSE recipient.[Status]
                    END,
                    [NextAttemptAtUtc] = CASE
                        WHEN attempt.[Status] IN (1, 5)
                            THEN COALESCE(recipient.[NextAttemptAtUtc], SYSDATETIMEOFFSET())
                        ELSE NULL
                    END,
                    [LeaseId] = NULL,
                    [LeaseExpiresAtUtc] = NULL,
                    [LastSafeErrorCode] = CASE
                        WHEN recipient.[Status] = 10
                            THEN COALESCE(recipient.[LastSafeErrorCode], 'campaign-cancelled')
                        WHEN recipient.[Status] IN (8, 9)
                            OR attempt.[Status] IN (2, 8)
                            THEN COALESCE(recipient.[LastSafeErrorCode], 'provider-outcome-unknown')
                        ELSE recipient.[LastSafeErrorCode]
                    END
                FROM [notifications].[NotificationRecipients] AS recipient
                LEFT JOIN [notifications].[NotificationDeliveryAttempts] AS attempt
                    ON attempt.[Id] = recipient.[CurrentAttemptId]
                WHERE recipient.[Status] IN (8, 9, 10);

                UPDATE [notifications].[NotificationCampaigns]
                SET [Status] = 5,
                    [CompletedAtUtc] = COALESCE([CompletedAtUtc], SYSDATETIMEOFFSET()),
                    [AudienceLeaseId] = NULL,
                    [AudienceLeaseExpiresAtUtc] = NULL
                WHERE [Status] = 8;

                UPDATE campaign
                SET [PendingCount] = aggregate.[PendingCount],
                    [SignalRDispatchedCount] = aggregate.[SignalRDispatchedCount],
                    [FcmAcceptedCount] = aggregate.[FcmAcceptedCount],
                    [FailedCount] = aggregate.[FailedCount],
                    [SkippedCount] = aggregate.[SkippedCount],
                    [ExpiredCount] = aggregate.[ExpiredCount]
                FROM [notifications].[NotificationCampaigns] AS campaign
                CROSS APPLY
                (
                    SELECT
                        COALESCE(SUM(CASE WHEN recipient.[Status] IN (1, 2) THEN 1 ELSE 0 END), 0) AS [PendingCount],
                        COALESCE(SUM(CASE WHEN recipient.[Status] = 3 THEN 1 ELSE 0 END), 0) AS [SignalRDispatchedCount],
                        COALESCE(SUM(CASE WHEN recipient.[Status] = 4 THEN 1 ELSE 0 END), 0) AS [FcmAcceptedCount],
                        COALESCE(SUM(CASE WHEN recipient.[Status] = 5 THEN 1 ELSE 0 END), 0) AS [FailedCount],
                        COALESCE(SUM(CASE WHEN recipient.[Status] = 6 THEN 1 ELSE 0 END), 0) AS [SkippedCount],
                        COALESCE(SUM(CASE WHEN recipient.[Status] = 7 THEN 1 ELSE 0 END), 0) AS [ExpiredCount]
                    FROM [notifications].[NotificationRecipients] AS recipient
                    WHERE recipient.[CampaignId] = campaign.[Id]
                ) AS aggregate;
                """);

            migrationBuilder.DropTable(
                name: "NotificationDeliveryAttempts",
                schema: "notifications");

            migrationBuilder.DropIndex(
                name: "UX_NotificationRecipients_CurrentAttempt",
                schema: "notifications",
                table: "NotificationRecipients");

            migrationBuilder.DropIndex(
                name: "UX_NotificationRecipients_DeliveryKey",
                schema: "notifications",
                table: "NotificationRecipients");

            migrationBuilder.DropColumn(
                name: "CurrentAttemptId",
                schema: "notifications",
                table: "NotificationRecipients");

            migrationBuilder.DropColumn(
                name: "DeliveryKey",
                schema: "notifications",
                table: "NotificationRecipients");

            migrationBuilder.AddCheckConstraint(
                name: "CK_NotificationRecipients_NextAttempt",
                schema: "notifications",
                table: "NotificationRecipients",
                sql: "([Status] IN (1, 2) AND [NextAttemptAtUtc] IS NOT NULL) OR ([Status] IN (3, 4, 5, 6, 7) AND [NextAttemptAtUtc] IS NULL)");

            migrationBuilder.AddCheckConstraint(
                name: "CK_NotificationRecipients_Status",
                schema: "notifications",
                table: "NotificationRecipients",
                sql: "[Status] BETWEEN 1 AND 7");

            migrationBuilder.AddCheckConstraint(
                name: "CK_NotificationCampaigns_Status",
                schema: "notifications",
                table: "NotificationCampaigns",
                sql: "[Status] BETWEEN 1 AND 7");
        }
    }
}
