using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace BotGlobal.Calling.Infrastructure.Migrations
{
    /// <inheritdoc />
    public partial class AddNqrbContactBook : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "NqrbContactEdges",
                schema: "calling",
                columns: table => new
                {
                    ApplicationKey = table.Column<string>(type: "varchar(80)", unicode: false, maxLength: 80, nullable: false),
                    OwnerMembershipId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    ContactMembershipId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    CreatedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_NqrbContactEdges", x => new { x.ApplicationKey, x.OwnerMembershipId, x.ContactMembershipId });
                });

            migrationBuilder.CreateTable(
                name: "NqrbContactInvites",
                schema: "calling",
                columns: table => new
                {
                    ApplicationKey = table.Column<string>(type: "varchar(80)", unicode: false, maxLength: 80, nullable: false),
                    CodeHash = table.Column<string>(type: "varchar(64)", unicode: false, maxLength: 64, nullable: false),
                    IssuerMembershipId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    CreatedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    ExpiresAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    ClaimedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    ClaimedByMembershipId = table.Column<Guid>(type: "uniqueidentifier", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_NqrbContactInvites", x => new { x.ApplicationKey, x.CodeHash });
                });

            migrationBuilder.CreateIndex(
                name: "IX_NqrbContactEdges_ApplicationKey_ContactMembershipId",
                schema: "calling",
                table: "NqrbContactEdges",
                columns: new[] { "ApplicationKey", "ContactMembershipId" });

            migrationBuilder.CreateIndex(
                name: "IX_NqrbContactEdges_ApplicationKey_OwnerMembershipId_CreatedAtUtc_ContactMembershipId",
                schema: "calling",
                table: "NqrbContactEdges",
                columns: new[] { "ApplicationKey", "OwnerMembershipId", "CreatedAtUtc", "ContactMembershipId" });

            migrationBuilder.CreateIndex(
                name: "IX_NqrbContactInvites_ApplicationKey_ClaimedByMembershipId",
                schema: "calling",
                table: "NqrbContactInvites",
                columns: new[] { "ApplicationKey", "ClaimedByMembershipId" });

            migrationBuilder.CreateIndex(
                name: "IX_NqrbContactInvites_ApplicationKey_IssuerMembershipId_ExpiresAtUtc",
                schema: "calling",
                table: "NqrbContactInvites",
                columns: new[] { "ApplicationKey", "IssuerMembershipId", "ExpiresAtUtc" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "NqrbContactInvites",
                schema: "calling");

            migrationBuilder.DropTable(
                name: "NqrbContactEdges",
                schema: "calling");
        }
    }
}
