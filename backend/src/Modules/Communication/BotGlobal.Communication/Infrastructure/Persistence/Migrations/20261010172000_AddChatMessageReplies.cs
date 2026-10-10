using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace BotGlobal.Communication.Infrastructure.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class AddChatMessageReplies : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            if (ActiveProvider != "Npgsql.EntityFrameworkCore.PostgreSQL")
                throw new NotSupportedException("Chat activation targets PostgreSQL only. Use the reviewed chat-only SQL artifact; do not apply the legacy migration chain.");

            migrationBuilder.AddColumn<Guid>(
                name: "ReplyToMessageId",
                schema: "communication",
                table: "ChatMessages",
                type: "uuid",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "ReplyToSenderSubjectId",
                schema: "communication",
                table: "ChatMessages",
                type: "character varying(200)",
                maxLength: 200,
                nullable: true,
                collation: "C");

            migrationBuilder.AddColumn<int>(
                name: "ReplyToKind",
                schema: "communication",
                table: "ChatMessages",
                type: "integer",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "ReplyToText",
                schema: "communication",
                table: "ChatMessages",
                type: "character varying(240)",
                maxLength: 240,
                nullable: true);

            migrationBuilder.AddColumn<int>(
                name: "ReplyToVoiceDurationMilliseconds",
                schema: "communication",
                table: "ChatMessages",
                type: "integer",
                nullable: true);

            migrationBuilder.CreateIndex(
                name: "IX_ChatMessages_ApplicationId_ReplyToMessageId",
                schema: "communication",
                table: "ChatMessages",
                columns: new[] { "ApplicationId", "ReplyToMessageId" });

            migrationBuilder.AddForeignKey(
                name: "FK_ChatMessages_ChatMessages_ApplicationId_ReplyToMessageId",
                schema: "communication",
                table: "ChatMessages",
                columns: new[] { "ApplicationId", "ReplyToMessageId" },
                principalSchema: "communication",
                principalTable: "ChatMessages",
                principalColumns: new[] { "ApplicationId", "Id" },
                onDelete: ReferentialAction.Restrict);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            if (ActiveProvider != "Npgsql.EntityFrameworkCore.PostgreSQL")
                throw new NotSupportedException("Chat activation targets PostgreSQL only. Use the reviewed chat-only SQL artifact; do not apply the legacy migration chain.");

            migrationBuilder.DropForeignKey(
                name: "FK_ChatMessages_ChatMessages_ApplicationId_ReplyToMessageId",
                schema: "communication",
                table: "ChatMessages");

            migrationBuilder.DropIndex(
                name: "IX_ChatMessages_ApplicationId_ReplyToMessageId",
                schema: "communication",
                table: "ChatMessages");

            migrationBuilder.DropColumn(name: "ReplyToMessageId", schema: "communication", table: "ChatMessages");
            migrationBuilder.DropColumn(name: "ReplyToSenderSubjectId", schema: "communication", table: "ChatMessages");
            migrationBuilder.DropColumn(name: "ReplyToKind", schema: "communication", table: "ChatMessages");
            migrationBuilder.DropColumn(name: "ReplyToText", schema: "communication", table: "ChatMessages");
            migrationBuilder.DropColumn(name: "ReplyToVoiceDurationMilliseconds", schema: "communication", table: "ChatMessages");
        }
    }
}
