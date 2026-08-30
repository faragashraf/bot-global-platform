using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace BotGlobal.Notifications.Infrastructure.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class InitialNotificationCampaigns : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.EnsureSchema(
                name: "notifications");

            migrationBuilder.CreateTable(
                name: "NotificationCampaigns",
                schema: "notifications",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    PlatformClientId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    PlatformClientKeySnapshot = table.Column<string>(type: "nvarchar(100)", maxLength: 100, nullable: false),
                    PlatformClientDisplayNameSnapshot = table.Column<string>(type: "nvarchar(200)", maxLength: 200, nullable: false),
                    AudienceKind = table.Column<int>(type: "int", nullable: false),
                    AudienceAsOfUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    TitleAr = table.Column<string>(type: "nvarchar(200)", maxLength: 200, nullable: false),
                    TitleEn = table.Column<string>(type: "nvarchar(200)", maxLength: 200, nullable: false),
                    BodyAr = table.Column<string>(type: "nvarchar(4000)", maxLength: 4000, nullable: false),
                    BodyEn = table.Column<string>(type: "nvarchar(4000)", maxLength: 4000, nullable: false),
                    Type = table.Column<string>(type: "nvarchar(100)", maxLength: 100, nullable: false),
                    Priority = table.Column<int>(type: "int", nullable: false),
                    Status = table.Column<int>(type: "int", nullable: false),
                    IdempotencyKey = table.Column<string>(type: "varchar(200)", unicode: false, maxLength: 200, nullable: false),
                    RequestFingerprint = table.Column<string>(type: "varchar(64)", unicode: false, maxLength: 64, nullable: false),
                    CreatedByUserId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    CreatedByDisplayNameSnapshot = table.Column<string>(type: "nvarchar(200)", maxLength: 200, nullable: false),
                    CreatedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    ExpiresAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    ProcessingStartedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    CompletedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    AudienceSubjectCount = table.Column<int>(type: "int", nullable: false),
                    AudienceDeviceCount = table.Column<int>(type: "int", nullable: false),
                    PushCapableDeviceCount = table.Column<int>(type: "int", nullable: false),
                    PendingCount = table.Column<int>(type: "int", nullable: false),
                    SignalRDispatchedCount = table.Column<int>(type: "int", nullable: false),
                    FcmAcceptedCount = table.Column<int>(type: "int", nullable: false),
                    FailedCount = table.Column<int>(type: "int", nullable: false),
                    SkippedCount = table.Column<int>(type: "int", nullable: false),
                    ExpiredCount = table.Column<int>(type: "int", nullable: false),
                    AudienceExpansionCursor = table.Column<Guid>(type: "uniqueidentifier", nullable: true),
                    IsAudienceExpansionComplete = table.Column<bool>(type: "bit", nullable: false),
                    AudienceLeaseId = table.Column<Guid>(type: "uniqueidentifier", nullable: true),
                    AudienceLeaseExpiresAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    RowVersion = table.Column<byte[]>(type: "rowversion", rowVersion: true, nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_NotificationCampaigns", x => x.Id);
                    table.CheckConstraint("CK_NotificationCampaigns_AudienceKind", "[AudienceKind] = 1");
                    table.CheckConstraint("CK_NotificationCampaigns_AudienceLease", "([AudienceLeaseId] IS NULL AND [AudienceLeaseExpiresAtUtc] IS NULL) OR ([AudienceLeaseId] IS NOT NULL AND [AudienceLeaseExpiresAtUtc] IS NOT NULL)");
                    table.CheckConstraint("CK_NotificationCampaigns_Counts", "[AudienceSubjectCount] >= 0 AND [AudienceDeviceCount] >= 0 AND [PushCapableDeviceCount] >= 0 AND [PendingCount] >= 0 AND [SignalRDispatchedCount] >= 0 AND [FcmAcceptedCount] >= 0 AND [FailedCount] >= 0 AND [SkippedCount] >= 0 AND [ExpiredCount] >= 0");
                    table.CheckConstraint("CK_NotificationCampaigns_Lifetime", "[ExpiresAtUtc] > [CreatedAtUtc]");
                    table.CheckConstraint("CK_NotificationCampaigns_Priority", "[Priority] IN (1, 2)");
                    table.CheckConstraint("CK_NotificationCampaigns_Status", "[Status] BETWEEN 1 AND 7");
                });

            migrationBuilder.CreateTable(
                name: "NotificationRecipients",
                schema: "notifications",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    CampaignId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    MobileDeviceId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    InstallationIdSnapshot = table.Column<string>(type: "nvarchar(200)", maxLength: 200, nullable: false),
                    PlatformSnapshot = table.Column<string>(type: "nvarchar(50)", maxLength: 50, nullable: false),
                    DeviceNameSnapshot = table.Column<string>(type: "nvarchar(250)", maxLength: 250, nullable: true),
                    Status = table.Column<int>(type: "int", nullable: false),
                    AttemptCount = table.Column<int>(type: "int", nullable: false),
                    NextAttemptAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    LastAttemptAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    LastTransport = table.Column<string>(type: "varchar(32)", unicode: false, maxLength: 32, nullable: true),
                    LastSafeErrorCode = table.Column<string>(type: "varchar(100)", unicode: false, maxLength: 100, nullable: true),
                    DispatchedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    ExpiresAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    LeaseId = table.Column<Guid>(type: "uniqueidentifier", nullable: true),
                    LeaseExpiresAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    RowVersion = table.Column<byte[]>(type: "rowversion", rowVersion: true, nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_NotificationRecipients", x => x.Id);
                    table.CheckConstraint("CK_NotificationRecipients_AttemptCount", "[AttemptCount] >= 0");
                    table.CheckConstraint("CK_NotificationRecipients_Lease", "([LeaseId] IS NULL AND [LeaseExpiresAtUtc] IS NULL) OR ([LeaseId] IS NOT NULL AND [LeaseExpiresAtUtc] IS NOT NULL)");
                    table.CheckConstraint("CK_NotificationRecipients_NextAttempt", "([Status] IN (1, 2) AND [NextAttemptAtUtc] IS NOT NULL) OR ([Status] IN (3, 4, 5, 6, 7) AND [NextAttemptAtUtc] IS NULL)");
                    table.CheckConstraint("CK_NotificationRecipients_Status", "[Status] BETWEEN 1 AND 7");
                    table.ForeignKey(
                        name: "FK_NotificationRecipients_NotificationCampaigns_CampaignId",
                        column: x => x.CampaignId,
                        principalSchema: "notifications",
                        principalTable: "NotificationCampaigns",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "IX_NotificationCampaigns_AudienceWork",
                schema: "notifications",
                table: "NotificationCampaigns",
                columns: new[] { "Status", "AudienceLeaseExpiresAtUtc" });

            migrationBuilder.CreateIndex(
                name: "IX_NotificationCampaigns_PlatformClient_CreatedAtUtc",
                schema: "notifications",
                table: "NotificationCampaigns",
                columns: new[] { "PlatformClientId", "CreatedAtUtc" });

            migrationBuilder.CreateIndex(
                name: "UX_NotificationCampaigns_Admin_IdempotencyKey",
                schema: "notifications",
                table: "NotificationCampaigns",
                columns: new[] { "CreatedByUserId", "IdempotencyKey" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_NotificationRecipients_DispatchWork",
                schema: "notifications",
                table: "NotificationRecipients",
                columns: new[] { "Status", "NextAttemptAtUtc", "LeaseExpiresAtUtc" });

            migrationBuilder.CreateIndex(
                name: "UX_NotificationRecipients_Campaign_Device",
                schema: "notifications",
                table: "NotificationRecipients",
                columns: new[] { "CampaignId", "MobileDeviceId" },
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "NotificationRecipients",
                schema: "notifications");

            migrationBuilder.DropTable(
                name: "NotificationCampaigns",
                schema: "notifications");
        }
    }
}
