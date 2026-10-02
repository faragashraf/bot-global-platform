using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace BotGlobal.Calling.Infrastructure.Migrations
{
    /// <inheritdoc />
    public partial class AddNqrbContactNicknames : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "Nickname",
                schema: "calling",
                table: "NqrbContactEdges",
                type: "nvarchar(80)",
                maxLength: 80,
                nullable: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "Nickname",
                schema: "calling",
                table: "NqrbContactEdges");
        }
    }
}
