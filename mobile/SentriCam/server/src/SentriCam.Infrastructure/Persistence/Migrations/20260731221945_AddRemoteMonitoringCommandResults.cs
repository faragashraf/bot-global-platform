using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace SentriCam.Infrastructure.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class AddRemoteMonitoringCommandResults : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "ResultCode",
                table: "DeviceCommands",
                type: "nvarchar(100)",
                maxLength: 100,
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "ResultJson",
                table: "DeviceCommands",
                type: "nvarchar(max)",
                nullable: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "ResultCode",
                table: "DeviceCommands");

            migrationBuilder.DropColumn(
                name: "ResultJson",
                table: "DeviceCommands");
        }
    }
}
