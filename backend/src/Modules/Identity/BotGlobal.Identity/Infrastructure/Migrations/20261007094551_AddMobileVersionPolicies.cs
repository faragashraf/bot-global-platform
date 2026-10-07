using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace BotGlobal.Identity.Infrastructure.Migrations
{
    /// <inheritdoc />
    public partial class AddMobileVersionPolicies : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "MobileVersionPolicies",
                schema: "identity",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    ApplicationKey = table.Column<string>(type: "varchar(80)", unicode: false, maxLength: 80, nullable: false),
                    Platform = table.Column<string>(type: "varchar(32)", unicode: false, maxLength: 32, nullable: false),
                    LatestVersion = table.Column<string>(type: "varchar(32)", unicode: false, maxLength: 32, nullable: false),
                    MinimumSupportedVersion = table.Column<string>(type: "varchar(32)", unicode: false, maxLength: 32, nullable: false),
                    Message = table.Column<string>(type: "nvarchar(500)", maxLength: 500, nullable: true),
                    StoreDestination = table.Column<string>(type: "varchar(500)", unicode: false, maxLength: 500, nullable: true),
                    IsActive = table.Column<bool>(type: "bit", nullable: false),
                    CreatedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    UpdatedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    UpdatedByUserId = table.Column<Guid>(type: "uniqueidentifier", nullable: true),
                    UpdatedByDisplayName = table.Column<string>(type: "nvarchar(200)", maxLength: 200, nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_MobileVersionPolicies", x => x.Id);
                });

            migrationBuilder.CreateIndex(
                name: "IX_MobileVersionPolicies_ApplicationKey_Platform",
                schema: "identity",
                table: "MobileVersionPolicies",
                columns: new[] { "ApplicationKey", "Platform" },
                unique: true);

            migrationBuilder.InsertData(
                schema: "identity",
                table: "MobileVersionPolicies",
                columns: new[]
                {
                    "Id",
                    "ApplicationKey",
                    "Platform",
                    "LatestVersion",
                    "MinimumSupportedVersion",
                    "Message",
                    "StoreDestination",
                    "IsActive",
                    "CreatedAtUtc",
                    "UpdatedAtUtc"
                },
                values: new object[,]
                {
                    {
                        new Guid("f6a6b29c-6f0b-4ec2-a6dc-9286650d0e4a"),
                        "nqrb",
                        "android",
                        "0.2.6",
                        "0.2.6",
                        "يتوفر تحديث جديد لتطبيق نقرب. حدّث التطبيق للمتابعة.",
                        "https://play.google.com/store/apps/details?id=com.botglobal.nqrb",
                        true,
                        new DateTimeOffset(new DateTime(2026, 10, 7, 0, 0, 0, DateTimeKind.Unspecified), TimeSpan.Zero),
                        new DateTimeOffset(new DateTime(2026, 10, 7, 0, 0, 0, DateTimeKind.Unspecified), TimeSpan.Zero)
                    },
                    {
                        new Guid("e97f6fb3-06c0-4c3f-9f5f-0f3ec06f9f8b"),
                        "family-games",
                        "android",
                        "0.1.0",
                        "0.1.0",
                        null,
                        null,
                        true,
                        new DateTimeOffset(new DateTime(2026, 10, 7, 0, 0, 0, DateTimeKind.Unspecified), TimeSpan.Zero),
                        new DateTimeOffset(new DateTime(2026, 10, 7, 0, 0, 0, DateTimeKind.Unspecified), TimeSpan.Zero)
                    },
                    {
                        new Guid("8963cf70-2e3c-447e-8a32-f69c2ce5f7fa"),
                        "family-games",
                        "ios",
                        "0.1.0",
                        "0.1.0",
                        null,
                        null,
                        true,
                        new DateTimeOffset(new DateTime(2026, 10, 7, 0, 0, 0, DateTimeKind.Unspecified), TimeSpan.Zero),
                        new DateTimeOffset(new DateTime(2026, 10, 7, 0, 0, 0, DateTimeKind.Unspecified), TimeSpan.Zero)
                    }
                });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "MobileVersionPolicies",
                schema: "identity");
        }
    }
}
