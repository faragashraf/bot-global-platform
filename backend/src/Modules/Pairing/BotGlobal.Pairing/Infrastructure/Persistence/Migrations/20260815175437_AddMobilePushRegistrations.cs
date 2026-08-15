using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace BotGlobal.Pairing.Infrastructure.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class AddMobilePushRegistrations : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "MobilePushRegistrations",
                schema: "pairing",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    MobileDeviceId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    Provider = table.Column<string>(type: "varchar(32)", unicode: false, maxLength: 32, nullable: false),
                    RegistrationToken = table.Column<string>(type: "varchar(2048)", unicode: false, maxLength: 2048, nullable: false),
                    CreatedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    UpdatedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    InvalidatedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_MobilePushRegistrations", x => x.Id);
                    table.ForeignKey(
                        name: "FK_MobilePushRegistrations_MobileDevices_MobileDeviceId",
                        column: x => x.MobileDeviceId,
                        principalSchema: "pairing",
                        principalTable: "MobileDevices",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "IX_MobilePushRegistrations_Provider_Invalidated",
                schema: "pairing",
                table: "MobilePushRegistrations",
                columns: new[] { "Provider", "InvalidatedAtUtc" });

            migrationBuilder.CreateIndex(
                name: "UX_MobilePushRegistrations_Device_Provider",
                schema: "pairing",
                table: "MobilePushRegistrations",
                columns: new[] { "MobileDeviceId", "Provider" },
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "MobilePushRegistrations",
                schema: "pairing");
        }
    }
}
