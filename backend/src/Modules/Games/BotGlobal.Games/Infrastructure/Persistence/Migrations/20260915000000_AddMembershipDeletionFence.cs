using System;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace BotGlobal.Games.Infrastructure.Persistence.Migrations
{
    [DbContext(typeof(GamesDbContext))]
    [Migration("20260915000000_AddMembershipDeletionFence")]
    public partial class AddMembershipDeletionFence : Migration
    {
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<long>(
                name: "AggregateVersion",
                schema: "games",
                table: "Sessions",
                type: "bigint",
                nullable: false,
                defaultValue: 0L);

            migrationBuilder.CreateTable(
                name: "MembershipDeletionFences",
                schema: "games",
                columns: table => new
                {
                    ApplicationKey = table.Column<string>(type: "nvarchar(80)", maxLength: 80, nullable: false),
                    MembershipId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    EstablishedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey(
                        "PK_MembershipDeletionFences",
                        x => new { x.ApplicationKey, x.MembershipId });
                });
        }

        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "MembershipDeletionFences",
                schema: "games");

            migrationBuilder.DropColumn(
                name: "AggregateVersion",
                schema: "games",
                table: "Sessions");
        }
    }
}
