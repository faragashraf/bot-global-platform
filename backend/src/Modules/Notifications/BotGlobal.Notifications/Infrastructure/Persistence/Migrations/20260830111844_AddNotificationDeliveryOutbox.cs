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

            migrationBuilder.Sql(
                """
                UPDATE recipient
                SET [DeliveryKey] =
                    LOWER(REPLACE(CONVERT(varchar(36), campaign.[PlatformClientId]), '-', ''))
                    + ':' + LOWER(REPLACE(CONVERT(varchar(36), recipient.[CampaignId]), '-', ''))
                    + ':' + LOWER(REPLACE(CONVERT(varchar(36), recipient.[MobileDeviceId]), '-', ''))
                FROM [notifications].[NotificationRecipients] AS recipient
                INNER JOIN [notifications].[NotificationCampaigns] AS campaign
                    ON campaign.[Id] = recipient.[CampaignId];
                """);

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
                    table.CheckConstraint("CK_NotificationDeliveryAttempts_Completion", "([Status] IN (1, 2) AND [CompletedAtUtc] IS NULL) OR ([Status] BETWEEN 3 AND 9 AND [CompletedAtUtc] IS NOT NULL)");
                    table.CheckConstraint("CK_NotificationDeliveryAttempts_Invocation", "([Status] = 1 AND [ProviderInvocationStartedAtUtc] IS NULL) OR ([Status] BETWEEN 2 AND 8 AND [ProviderInvocationStartedAtUtc] IS NOT NULL) OR ([Status] = 9)");
                    table.CheckConstraint("CK_NotificationDeliveryAttempts_Status", "[Status] BETWEEN 1 AND 9");
                    table.ForeignKey(
                        name: "FK_NotificationDeliveryAttempts_NotificationRecipients_NotificationRecipientId",
                        column: x => x.NotificationRecipientId,
                        principalSchema: "notifications",
                        principalTable: "NotificationRecipients",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.Sql(
                """
                UPDATE [notifications].[NotificationRecipients]
                SET [CurrentAttemptId] = NEWID()
                WHERE [AttemptCount] > 0
                    AND [Status] IN (2, 3, 4, 5, 6)
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
                    AND recipient.[AttemptCount] > 0
                    AND recipient.[Status] IN (2, 3, 4, 5, 6);
                """);

            migrationBuilder.CreateIndex(
                name: "UX_NotificationRecipients_CurrentAttempt",
                schema: "notifications",
                table: "NotificationRecipients",
                column: "CurrentAttemptId",
                unique: true,
                filter: "[CurrentAttemptId] IS NOT NULL");

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
                sql: "[Status] IN (1, 2, 7) OR [CurrentAttemptId] IS NOT NULL");

            migrationBuilder.AddCheckConstraint(
                name: "CK_NotificationRecipients_NextAttempt",
                schema: "notifications",
                table: "NotificationRecipients",
                sql: "([Status] IN (1, 2) AND [NextAttemptAtUtc] IS NOT NULL) OR ([Status] BETWEEN 3 AND 9 AND [NextAttemptAtUtc] IS NULL)");

            migrationBuilder.AddCheckConstraint(
                name: "CK_NotificationRecipients_Status",
                schema: "notifications",
                table: "NotificationRecipients",
                sql: "[Status] BETWEEN 1 AND 9");

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

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
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
        }
    }
}
