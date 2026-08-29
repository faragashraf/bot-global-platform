using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace BotGlobal.Games.Infrastructure.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class AddGameInvitations : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "RequiredEntitlement",
                schema: "games",
                table: "Sessions",
                type: "nvarchar(120)",
                maxLength: 120,
                nullable: true);

            migrationBuilder.CreateTable(
                name: "Invitations",
                schema: "games",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    SessionId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    ApplicationKey = table.Column<string>(type: "nvarchar(80)", maxLength: 80, nullable: false),
                    TokenHash = table.Column<string>(type: "nvarchar(64)", maxLength: 64, nullable: false),
                    CreatedByMembershipId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    CreatedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    ExpiresAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    RevokedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    ConsumedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Invitations", x => x.Id);
                    table.ForeignKey(
                        name: "FK_Invitations_Sessions_SessionId",
                        column: x => x.SessionId,
                        principalSchema: "games",
                        principalTable: "Sessions",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "IX_Invitations_ApplicationKey_SessionId_ExpiresAtUtc",
                schema: "games",
                table: "Invitations",
                columns: new[] { "ApplicationKey", "SessionId", "ExpiresAtUtc" });

            migrationBuilder.CreateIndex(
                name: "IX_Invitations_SessionId",
                schema: "games",
                table: "Invitations",
                column: "SessionId");

            migrationBuilder.CreateIndex(
                name: "IX_Invitations_TokenHash",
                schema: "games",
                table: "Invitations",
                column: "TokenHash",
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "Invitations",
                schema: "games");

            migrationBuilder.DropColumn(
                name: "RequiredEntitlement",
                schema: "games",
                table: "Sessions");
        }
    }
}
