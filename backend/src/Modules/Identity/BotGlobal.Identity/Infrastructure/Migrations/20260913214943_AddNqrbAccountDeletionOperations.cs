using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace BotGlobal.Identity.Infrastructure.Migrations
{
    /// <inheritdoc />
    public partial class AddNqrbAccountDeletionOperations : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "ApplicationAccountDeletionRequests",
                schema: "identity",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    MembershipId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    GlobalUserId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    ApplicationKey = table.Column<string>(type: "varchar(80)", unicode: false, maxLength: 80, nullable: false),
                    SubjectId = table.Column<string>(type: "nvarchar(160)", maxLength: 160, nullable: false),
                    MobileDeviceIdsJson = table.Column<string>(type: "nvarchar(max)", nullable: false),
                    RequestedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    AccessRevokedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    NextAttemptAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    LastAttemptAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    AttemptCount = table.Column<int>(type: "int", nullable: false),
                    LastSafeErrorCode = table.Column<string>(type: "varchar(100)", unicode: false, maxLength: 100, nullable: true),
                    ProcessorLeaseId = table.Column<Guid>(type: "uniqueidentifier", nullable: true),
                    ProcessorLeaseExpiresAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    RowVersion = table.Column<byte[]>(type: "rowversion", rowVersion: true, nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_ApplicationAccountDeletionRequests", x => x.Id);
                });

            migrationBuilder.CreateIndex(
                name: "IX_ApplicationAccountDeletionRequests_MembershipId",
                schema: "identity",
                table: "ApplicationAccountDeletionRequests",
                column: "MembershipId",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_ApplicationAccountDeletionRequests_NextAttemptAtUtc",
                schema: "identity",
                table: "ApplicationAccountDeletionRequests",
                column: "NextAttemptAtUtc");

            migrationBuilder.CreateIndex(
                name: "IX_ApplicationMemberships_GlobalUserId",
                schema: "identity",
                table: "ApplicationMemberships",
                column: "GlobalUserId");

            migrationBuilder.AddForeignKey(
                name: "FK_ApplicationMemberships_Users_GlobalUserId",
                schema: "identity",
                table: "ApplicationMemberships",
                column: "GlobalUserId",
                principalSchema: "identity",
                principalTable: "Users",
                principalColumn: "Id",
                onDelete: ReferentialAction.Restrict);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropForeignKey(
                name: "FK_ApplicationMemberships_Users_GlobalUserId",
                schema: "identity",
                table: "ApplicationMemberships");

            migrationBuilder.DropIndex(
                name: "IX_ApplicationMemberships_GlobalUserId",
                schema: "identity",
                table: "ApplicationMemberships");

            migrationBuilder.DropTable(
                name: "ApplicationAccountDeletionRequests",
                schema: "identity");
        }
    }
}
