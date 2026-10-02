using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace BotGlobal.Calling.Infrastructure.Migrations
{
    /// <inheritdoc />
    public partial class AddNqrbGuestCallHistory : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<bool>(
                name: "IsGuestCall",
                schema: "calling",
                table: "Calls",
                type: "bit",
                nullable: false,
                defaultValue: false);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "IsGuestCall",
                schema: "calling",
                table: "Calls");
        }
    }
}
