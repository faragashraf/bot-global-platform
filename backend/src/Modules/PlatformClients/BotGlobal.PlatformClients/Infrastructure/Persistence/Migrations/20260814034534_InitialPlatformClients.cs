using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace BotGlobal.PlatformClients.Infrastructure.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class InitialPlatformClients : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.EnsureSchema(
                name: "platform_clients");

            migrationBuilder.CreateTable(
                name: "Clients",
                schema: "platform_clients",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    ClientKey = table.Column<string>(type: "varchar(100)", unicode: false, maxLength: 100, nullable: false),
                    DisplayName = table.Column<string>(type: "nvarchar(200)", maxLength: 200, nullable: false),
                    Status = table.Column<string>(type: "varchar(16)", unicode: false, maxLength: 16, nullable: false),
                    CreatedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    DisabledAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Clients", x => x.Id);
                    table.CheckConstraint("CK_PlatformClients_Status", "[Status] IN ('Active','Disabled')");
                });

            migrationBuilder.CreateTable(
                name: "Capabilities",
                schema: "platform_clients",
                columns: table => new
                {
                    ClientId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    Capability = table.Column<string>(type: "varchar(100)", unicode: false, maxLength: 100, nullable: false),
                    GrantedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Capabilities", x => new { x.ClientId, x.Capability });
                    table.ForeignKey(
                        name: "FK_Capabilities_Clients_ClientId",
                        column: x => x.ClientId,
                        principalSchema: "platform_clients",
                        principalTable: "Clients",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "Credentials",
                schema: "platform_clients",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    ClientId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    SecretHash = table.Column<byte[]>(type: "binary(32)", nullable: false),
                    CreatedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    ExpiresAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    RevokedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Credentials", x => x.Id);
                    table.CheckConstraint("CK_PlatformClientCredentials_Expiry", "[ExpiresAtUtc] IS NULL OR [ExpiresAtUtc] > [CreatedAtUtc]");
                    table.CheckConstraint("CK_PlatformClientCredentials_RevokeTime", "[RevokedAtUtc] IS NULL OR [RevokedAtUtc] >= [CreatedAtUtc]");
                    table.ForeignKey(
                        name: "FK_Credentials_Clients_ClientId",
                        column: x => x.ClientId,
                        principalSchema: "platform_clients",
                        principalTable: "Clients",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "UX_PlatformClients_ClientKey",
                schema: "platform_clients",
                table: "Clients",
                column: "ClientKey",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_PlatformClientCredentials_Client_Usability",
                schema: "platform_clients",
                table: "Credentials",
                columns: new[] { "ClientId", "RevokedAtUtc", "ExpiresAtUtc" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "Capabilities",
                schema: "platform_clients");

            migrationBuilder.DropTable(
                name: "Credentials",
                schema: "platform_clients");

            migrationBuilder.DropTable(
                name: "Clients",
                schema: "platform_clients");
        }
    }
}
