using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace BotGlobal.Pairing.Infrastructure.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class AddMobileDeviceAuditEntries : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "MobileDeviceAuditEntries",
                schema: "pairing",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    MobileDeviceId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    PlatformClientId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    Kind = table.Column<string>(type: "nvarchar(60)", maxLength: 60, nullable: false),
                    ActorType = table.Column<string>(type: "nvarchar(20)", maxLength: 20, nullable: false),
                    ActorDisplayName = table.Column<string>(type: "nvarchar(200)", maxLength: 200, nullable: true),
                    Detail = table.Column<string>(type: "nvarchar(300)", maxLength: 300, nullable: true),
                    OccurredAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_MobileDeviceAuditEntries", x => x.Id);
                    table.ForeignKey(
                        name: "FK_MobileDeviceAuditEntries_MobileDevices_MobileDeviceId",
                        column: x => x.MobileDeviceId,
                        principalSchema: "pairing",
                        principalTable: "MobileDevices",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "IX_MobileDeviceAuditEntries_MobileDeviceId_OccurredAtUtc",
                schema: "pairing",
                table: "MobileDeviceAuditEntries",
                columns: new[] { "MobileDeviceId", "OccurredAtUtc" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "MobileDeviceAuditEntries",
                schema: "pairing");
        }
    }
}
