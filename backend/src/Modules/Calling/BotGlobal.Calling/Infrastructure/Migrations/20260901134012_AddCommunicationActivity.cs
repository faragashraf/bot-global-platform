using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace BotGlobal.Calling.Infrastructure.Migrations
{
    /// <inheritdoc />
    public partial class AddCommunicationActivity : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.EnsureSchema(
                name: "calling");

            migrationBuilder.CreateTable(
                name: "Calls",
                schema: "calling",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    ApplicationId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    ApplicationKey = table.Column<string>(type: "varchar(80)", unicode: false, maxLength: 80, nullable: false),
                    CreatedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    AnsweredAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    EndedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    State = table.Column<string>(type: "varchar(16)", unicode: false, maxLength: 16, nullable: false),
                    Outcome = table.Column<string>(type: "varchar(16)", unicode: false, maxLength: 16, nullable: true),
                    EndReason = table.Column<string>(type: "varchar(40)", unicode: false, maxLength: 40, nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Calls", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "UsageCounterPeriods",
                schema: "calling",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    ApplicationId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    MembershipId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    StartedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    EndedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    ResetReason = table.Column<string>(type: "varchar(16)", unicode: false, maxLength: 16, nullable: false),
                    ScheduledResetAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    ScheduledLocalDateTime = table.Column<DateTime>(type: "datetime2", nullable: true),
                    ScheduledTimeZoneId = table.Column<string>(type: "varchar(100)", unicode: false, maxLength: 100, nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_UsageCounterPeriods", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "CallParticipants",
                schema: "calling",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    CallId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    MembershipId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    Role = table.Column<string>(type: "varchar(16)", unicode: false, maxLength: 16, nullable: false),
                    DisplayNameSnapshot = table.Column<string>(type: "nvarchar(160)", maxLength: 160, nullable: false),
                    JoinedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    AnsweredAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_CallParticipants", x => x.Id);
                    table.UniqueConstraint("AK_CallParticipants_CallId_MembershipId", x => new { x.CallId, x.MembershipId });
                    table.ForeignKey(
                        name: "FK_CallParticipants_Calls_CallId",
                        column: x => x.CallId,
                        principalSchema: "calling",
                        principalTable: "Calls",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "CallUsageReports",
                schema: "calling",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    CallId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    MembershipId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    BytesSent = table.Column<long>(type: "bigint", nullable: false),
                    BytesReceived = table.Column<long>(type: "bigint", nullable: false),
                    ConnectedDurationSeconds = table.Column<long>(type: "bigint", nullable: false),
                    FinalizedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_CallUsageReports", x => x.Id);
                    table.CheckConstraint("CK_CallUsageReports_NonNegative", "[BytesSent] >= 0 AND [BytesReceived] >= 0 AND [ConnectedDurationSeconds] >= 0");
                    table.ForeignKey(
                        name: "FK_CallUsageReports_CallParticipants_CallId_MembershipId",
                        columns: x => new { x.CallId, x.MembershipId },
                        principalSchema: "calling",
                        principalTable: "CallParticipants",
                        principalColumns: new[] { "CallId", "MembershipId" });
                    table.ForeignKey(
                        name: "FK_CallUsageReports_Calls_CallId",
                        column: x => x.CallId,
                        principalSchema: "calling",
                        principalTable: "Calls",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "IX_CallParticipants_MembershipId_CallId",
                schema: "calling",
                table: "CallParticipants",
                columns: new[] { "MembershipId", "CallId" });

            migrationBuilder.CreateIndex(
                name: "IX_Calls_ApplicationId_CreatedAtUtc",
                schema: "calling",
                table: "Calls",
                columns: new[] { "ApplicationId", "CreatedAtUtc" });

            migrationBuilder.CreateIndex(
                name: "IX_CallUsageReports_CallId_MembershipId",
                schema: "calling",
                table: "CallUsageReports",
                columns: new[] { "CallId", "MembershipId" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_CallUsageReports_MembershipId_FinalizedAtUtc",
                schema: "calling",
                table: "CallUsageReports",
                columns: new[] { "MembershipId", "FinalizedAtUtc" });

            migrationBuilder.CreateIndex(
                name: "IX_UsageCounterPeriods_ApplicationId_MembershipId",
                schema: "calling",
                table: "UsageCounterPeriods",
                columns: new[] { "ApplicationId", "MembershipId" },
                unique: true,
                filter: "[EndedAtUtc] IS NULL");

            migrationBuilder.CreateIndex(
                name: "IX_UsageCounterPeriods_ApplicationId_MembershipId_StartedAtUtc",
                schema: "calling",
                table: "UsageCounterPeriods",
                columns: new[] { "ApplicationId", "MembershipId", "StartedAtUtc" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "CallUsageReports",
                schema: "calling");

            migrationBuilder.DropTable(
                name: "UsageCounterPeriods",
                schema: "calling");

            migrationBuilder.DropTable(
                name: "CallParticipants",
                schema: "calling");

            migrationBuilder.DropTable(
                name: "Calls",
                schema: "calling");
        }
    }
}
