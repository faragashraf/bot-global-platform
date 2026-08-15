using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace BotGlobal.Pairing.Infrastructure.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class AddExternalSubjectIdentity : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "ExternalSubjectId",
                schema: "pairing",
                table: "PairingChallenges",
                type: "nvarchar(200)",
                maxLength: 200,
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "ExternalSubjectId",
                schema: "pairing",
                table: "MobileDevices",
                type: "nvarchar(200)",
                maxLength: 200,
                nullable: true);

            migrationBuilder.CreateIndex(
                name: "IX_MobileDevices_PlatformClientId_ExternalSubjectId_RevokedAtUtc",
                schema: "pairing",
                table: "MobileDevices",
                columns: new[] { "PlatformClientId", "ExternalSubjectId", "RevokedAtUtc" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "IX_MobileDevices_PlatformClientId_ExternalSubjectId_RevokedAtUtc",
                schema: "pairing",
                table: "MobileDevices");

            migrationBuilder.DropColumn(
                name: "ExternalSubjectId",
                schema: "pairing",
                table: "PairingChallenges");

            migrationBuilder.DropColumn(
                name: "ExternalSubjectId",
                schema: "pairing",
                table: "MobileDevices");
        }
    }
}
