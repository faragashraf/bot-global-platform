using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace BotGlobal.Pairing.Infrastructure.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class InitialPairing : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.EnsureSchema(
                name: "pairing");

            migrationBuilder.CreateTable(
                name: "PairingChallenges",
                schema: "pairing",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    PlatformClientId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    TokenHash = table.Column<byte[]>(type: "binary(32)", nullable: false),
                    CorrelationReference = table.Column<string>(type: "nvarchar(200)", maxLength: 200, nullable: true),
                    CreatedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    ExpiresAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    CompletedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    Status = table.Column<string>(type: "varchar(16)", unicode: false, maxLength: 16, nullable: false),
                    MobilePlatform = table.Column<string>(type: "varchar(20)", unicode: false, maxLength: 20, nullable: true),
                    MobileInstallationId = table.Column<string>(type: "varchar(128)", unicode: false, maxLength: 128, nullable: true),
                    MobileDeviceName = table.Column<string>(type: "nvarchar(120)", maxLength: 120, nullable: true),
                    MobileAppVersion = table.Column<string>(type: "varchar(50)", unicode: false, maxLength: 50, nullable: true),
                    ConcurrencyStamp = table.Column<Guid>(type: "uniqueidentifier", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_PairingChallenges", x => x.Id);
                    table.CheckConstraint("CK_PairingChallenges_Status", "[Status] IN ('Pending','Completed')");
                });

            migrationBuilder.CreateIndex(
                name: "IX_PairingChallenges_PlatformClient_Status_Expires",
                schema: "pairing",
                table: "PairingChallenges",
                columns: new[] { "PlatformClientId", "Status", "ExpiresAtUtc" });

            migrationBuilder.CreateIndex(
                name: "UX_PairingChallenges_TokenHash",
                schema: "pairing",
                table: "PairingChallenges",
                column: "TokenHash",
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "PairingChallenges",
                schema: "pairing");
        }
    }
}
