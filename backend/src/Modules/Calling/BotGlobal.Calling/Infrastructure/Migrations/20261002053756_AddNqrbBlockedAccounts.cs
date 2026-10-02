using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace BotGlobal.Calling.Infrastructure.Migrations
{
    /// <inheritdoc />
    public partial class AddNqrbBlockedAccounts : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "NqrbBlockedAccounts",
                schema: "calling",
                columns: table => new
                {
                    ApplicationKey = table.Column<string>(type: "varchar(80)", unicode: false, maxLength: 80, nullable: false),
                    OwnerMembershipId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    BlockedMembershipId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    CreatedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_NqrbBlockedAccounts", x => new { x.ApplicationKey, x.OwnerMembershipId, x.BlockedMembershipId });
                });

            migrationBuilder.CreateIndex(
                name: "IX_NqrbBlockedAccounts_ApplicationKey_BlockedMembershipId",
                schema: "calling",
                table: "NqrbBlockedAccounts",
                columns: new[] { "ApplicationKey", "BlockedMembershipId" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "NqrbBlockedAccounts",
                schema: "calling");
        }
    }
}
