using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace BotGlobal.Identity.Infrastructure.Migrations
{
    /// <inheritdoc />
    public partial class AddMobileApplicationIdentity : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "ApplicationMemberships",
                schema: "identity",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    ApplicationKey = table.Column<string>(type: "nvarchar(80)", maxLength: 80, nullable: false),
                    SubjectId = table.Column<string>(type: "nvarchar(160)", maxLength: 160, nullable: false),
                    DisplayName = table.Column<string>(type: "nvarchar(120)", maxLength: 120, nullable: false),
                    GlobalUserId = table.Column<Guid>(type: "uniqueidentifier", nullable: true),
                    IsGuest = table.Column<bool>(type: "bit", nullable: false),
                    IsActive = table.Column<bool>(type: "bit", nullable: false),
                    CreatedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    UpgradedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_ApplicationMemberships", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "MobileApplicationSessions",
                schema: "identity",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    MembershipId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    AccessTokenHash = table.Column<byte[]>(type: "varbinary(32)", maxLength: 32, nullable: false),
                    RefreshTokenHash = table.Column<byte[]>(type: "varbinary(32)", maxLength: 32, nullable: false),
                    AccessExpiresAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    RefreshExpiresAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    CreatedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    RevokedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_MobileApplicationSessions", x => x.Id);
                    table.ForeignKey(
                        name: "FK_MobileApplicationSessions_ApplicationMemberships_MembershipId",
                        column: x => x.MembershipId,
                        principalSchema: "identity",
                        principalTable: "ApplicationMemberships",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "IX_ApplicationMemberships_ApplicationKey_GlobalUserId",
                schema: "identity",
                table: "ApplicationMemberships",
                columns: new[] { "ApplicationKey", "GlobalUserId" },
                unique: true,
                filter: "[GlobalUserId] IS NOT NULL");

            migrationBuilder.CreateIndex(
                name: "IX_ApplicationMemberships_ApplicationKey_SubjectId",
                schema: "identity",
                table: "ApplicationMemberships",
                columns: new[] { "ApplicationKey", "SubjectId" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_MobileApplicationSessions_AccessTokenHash",
                schema: "identity",
                table: "MobileApplicationSessions",
                column: "AccessTokenHash",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_MobileApplicationSessions_MembershipId",
                schema: "identity",
                table: "MobileApplicationSessions",
                column: "MembershipId");

            migrationBuilder.CreateIndex(
                name: "IX_MobileApplicationSessions_RefreshTokenHash",
                schema: "identity",
                table: "MobileApplicationSessions",
                column: "RefreshTokenHash",
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "MobileApplicationSessions",
                schema: "identity");

            migrationBuilder.DropTable(
                name: "ApplicationMemberships",
                schema: "identity");
        }
    }
}
