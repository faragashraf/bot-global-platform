using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace BotGlobal.Games.Infrastructure.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class AddAutobusSessionPersistence : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "AutobusCommands",
                schema: "games",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    SessionId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    CommandId = table.Column<string>(type: "nvarchar(100)", maxLength: 100, nullable: false),
                    PlayerMembershipId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    Kind = table.Column<string>(type: "nvarchar(40)", maxLength: 40, nullable: false),
                    AcceptedVersion = table.Column<long>(type: "bigint", nullable: false),
                    AcceptedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AutobusCommands", x => x.Id);
                    table.ForeignKey(
                        name: "FK_AutobusCommands_Sessions_SessionId",
                        column: x => x.SessionId,
                        principalSchema: "games",
                        principalTable: "Sessions",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "AutobusSessionStates",
                schema: "games",
                columns: table => new
                {
                    SessionId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    RoundCount = table.Column<int>(type: "int", nullable: false),
                    RoundSeconds = table.Column<int>(type: "int", nullable: false),
                    Difficulty = table.Column<string>(type: "nvarchar(24)", maxLength: 24, nullable: false),
                    CategoriesJson = table.Column<string>(type: "nvarchar(max)", nullable: false),
                    UsedLettersJson = table.Column<string>(type: "nvarchar(max)", nullable: false),
                    CurrentRound = table.Column<int>(type: "int", nullable: false),
                    CurrentLetter = table.Column<string>(type: "nvarchar(8)", maxLength: 8, nullable: true),
                    Phase = table.Column<string>(type: "nvarchar(24)", maxLength: 24, nullable: false),
                    RoundStartedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    RoundDeadlineAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    GraceEndsAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    VoteDeadlineAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    RevealCategoryKey = table.Column<string>(type: "nvarchar(80)", maxLength: 80, nullable: true),
                    AnswersJson = table.Column<string>(type: "nvarchar(max)", nullable: false),
                    ScoresJson = table.Column<string>(type: "nvarchar(max)", nullable: false),
                    VotesJson = table.Column<string>(type: "nvarchar(max)", nullable: false),
                    TieMessageCode = table.Column<string>(type: "nvarchar(80)", maxLength: 80, nullable: true),
                    Version = table.Column<long>(type: "bigint", nullable: false),
                    ConcurrencyToken = table.Column<byte[]>(type: "rowversion", rowVersion: true, nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AutobusSessionStates", x => x.SessionId);
                    table.ForeignKey(
                        name: "FK_AutobusSessionStates_Sessions_SessionId",
                        column: x => x.SessionId,
                        principalSchema: "games",
                        principalTable: "Sessions",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "IX_AutobusCommands_SessionId_AcceptedVersion",
                schema: "games",
                table: "AutobusCommands",
                columns: new[] { "SessionId", "AcceptedVersion" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_AutobusCommands_SessionId_CommandId",
                schema: "games",
                table: "AutobusCommands",
                columns: new[] { "SessionId", "CommandId" },
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "AutobusCommands",
                schema: "games");

            migrationBuilder.DropTable(
                name: "AutobusSessionStates",
                schema: "games");
        }
    }
}
