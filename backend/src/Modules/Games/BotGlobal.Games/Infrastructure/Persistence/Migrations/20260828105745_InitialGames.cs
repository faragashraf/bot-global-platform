using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace BotGlobal.Games.Infrastructure.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class InitialGames : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.EnsureSchema(
                name: "games");

            migrationBuilder.CreateTable(
                name: "Sessions",
                schema: "games",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    ApplicationKey = table.Column<string>(type: "nvarchar(80)", maxLength: 80, nullable: false),
                    JoinCode = table.Column<string>(type: "nvarchar(12)", maxLength: 12, nullable: false),
                    GameType = table.Column<string>(type: "nvarchar(40)", maxLength: 40, nullable: false),
                    RulesetKey = table.Column<string>(type: "nvarchar(80)", maxLength: 80, nullable: false),
                    MaximumPlayers = table.Column<int>(type: "int", nullable: false),
                    CreatedByMembershipId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    Status = table.Column<string>(type: "nvarchar(24)", maxLength: 24, nullable: false),
                    CreatedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    LastActivityAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    StartedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    CompletedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    RematchRequestedByMembershipId = table.Column<Guid>(type: "uniqueidentifier", nullable: true),
                    MatchNumber = table.Column<int>(type: "int", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Sessions", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "Players",
                schema: "games",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    SessionId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    MembershipId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    DisplayName = table.Column<string>(type: "nvarchar(120)", maxLength: 120, nullable: false),
                    Seat = table.Column<int>(type: "int", nullable: false),
                    IsReady = table.Column<bool>(type: "bit", nullable: false),
                    IsConnected = table.Column<bool>(type: "bit", nullable: false),
                    JoinedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    LastSeenAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Players", x => x.Id);
                    table.ForeignKey(
                        name: "FK_Players_Sessions_SessionId",
                        column: x => x.SessionId,
                        principalSchema: "games",
                        principalTable: "Sessions",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "XoMoves",
                schema: "games",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    SessionId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    CommandId = table.Column<string>(type: "nvarchar(100)", maxLength: 100, nullable: false),
                    PlayerMembershipId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    Row = table.Column<int>(type: "int", nullable: false),
                    Column = table.Column<int>(type: "int", nullable: false),
                    AcceptedVersion = table.Column<long>(type: "bigint", nullable: false),
                    AcceptedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_XoMoves", x => x.Id);
                    table.ForeignKey(
                        name: "FK_XoMoves_Sessions_SessionId",
                        column: x => x.SessionId,
                        principalSchema: "games",
                        principalTable: "Sessions",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "XoSessionStates",
                schema: "games",
                columns: table => new
                {
                    SessionId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    BoardSize = table.Column<int>(type: "int", nullable: false),
                    WinLength = table.Column<int>(type: "int", nullable: false),
                    TurnTimeLimitSeconds = table.Column<int>(type: "int", nullable: true),
                    RematchEnabled = table.Column<bool>(type: "bit", nullable: false),
                    VoiceEnabled = table.Column<bool>(type: "bit", nullable: false),
                    RequiredEntitlement = table.Column<string>(type: "nvarchar(120)", maxLength: 120, nullable: true),
                    Version = table.Column<long>(type: "bigint", nullable: false),
                    MatchStatus = table.Column<string>(type: "nvarchar(24)", maxLength: 24, nullable: false),
                    ActivePlayerMembershipId = table.Column<Guid>(type: "uniqueidentifier", nullable: true),
                    WinnerMembershipId = table.Column<Guid>(type: "uniqueidentifier", nullable: true),
                    ConcurrencyToken = table.Column<byte[]>(type: "rowversion", rowVersion: true, nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_XoSessionStates", x => x.SessionId);
                    table.ForeignKey(
                        name: "FK_XoSessionStates_Sessions_SessionId",
                        column: x => x.SessionId,
                        principalSchema: "games",
                        principalTable: "Sessions",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "IX_Players_SessionId_MembershipId",
                schema: "games",
                table: "Players",
                columns: new[] { "SessionId", "MembershipId" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_Players_SessionId_Seat",
                schema: "games",
                table: "Players",
                columns: new[] { "SessionId", "Seat" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_Sessions_ApplicationKey_JoinCode",
                schema: "games",
                table: "Sessions",
                columns: new[] { "ApplicationKey", "JoinCode" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_Sessions_ApplicationKey_LastActivityAtUtc",
                schema: "games",
                table: "Sessions",
                columns: new[] { "ApplicationKey", "LastActivityAtUtc" });

            migrationBuilder.CreateIndex(
                name: "IX_XoMoves_SessionId_AcceptedVersion",
                schema: "games",
                table: "XoMoves",
                columns: new[] { "SessionId", "AcceptedVersion" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_XoMoves_SessionId_CommandId",
                schema: "games",
                table: "XoMoves",
                columns: new[] { "SessionId", "CommandId" },
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "Players",
                schema: "games");

            migrationBuilder.DropTable(
                name: "XoMoves",
                schema: "games");

            migrationBuilder.DropTable(
                name: "XoSessionStates",
                schema: "games");

            migrationBuilder.DropTable(
                name: "Sessions",
                schema: "games");
        }
    }
}
