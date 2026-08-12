using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace BotGlobal.Communication.Infrastructure.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class InitialCommunication : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.EnsureSchema(
                name: "communication");

            migrationBuilder.CreateTable(
                name: "Conversations",
                schema: "communication",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    Type = table.Column<string>(type: "varchar(16)", unicode: false, maxLength: 16, nullable: false),
                    Title = table.Column<string>(type: "nvarchar(200)", maxLength: 200, nullable: true),
                    DirectKey = table.Column<string>(type: "varchar(257)", unicode: false, maxLength: 257, nullable: true),
                    CreatedByUserId = table.Column<string>(type: "nvarchar(128)", maxLength: 128, nullable: false),
                    CreatedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    LastActivityAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Conversations", x => x.Id);
                    table.CheckConstraint("CK_Conversations_ActivityTime", "[LastActivityAtUtc] >= [CreatedAtUtc]");
                    table.CheckConstraint("CK_Conversations_Shape", "([Type] = 'Direct' AND [DirectKey] IS NOT NULL AND [Title] IS NULL) OR ([Type] = 'Group' AND [DirectKey] IS NULL AND [Title] IS NOT NULL)");
                    table.CheckConstraint("CK_Conversations_Type", "[Type] IN ('Direct','Group')");
                });

            migrationBuilder.CreateTable(
                name: "UserCommunicationPreferences",
                schema: "communication",
                columns: table => new
                {
                    UserId = table.Column<string>(type: "nvarchar(128)", maxLength: 128, nullable: false),
                    AllowVoiceCalls = table.Column<bool>(type: "bit", nullable: false, defaultValue: false),
                    AllowVideoCalls = table.Column<bool>(type: "bit", nullable: false, defaultValue: false),
                    UpdatedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_UserCommunicationPreferences", x => x.UserId);
                });

            migrationBuilder.CreateTable(
                name: "CallSessions",
                schema: "communication",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    ConversationId = table.Column<Guid>(type: "uniqueidentifier", nullable: true),
                    CallerUserId = table.Column<string>(type: "nvarchar(128)", maxLength: 128, nullable: false),
                    CalleeUserId = table.Column<string>(type: "nvarchar(128)", maxLength: 128, nullable: false),
                    ClientCallId = table.Column<string>(type: "nvarchar(100)", maxLength: 100, nullable: false),
                    Kind = table.Column<string>(type: "varchar(16)", unicode: false, maxLength: 16, nullable: false),
                    Status = table.Column<string>(type: "varchar(16)", unicode: false, maxLength: 16, nullable: false),
                    EndReason = table.Column<string>(type: "varchar(24)", unicode: false, maxLength: 24, nullable: true),
                    StartedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    AnsweredAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    EndedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_CallSessions", x => x.Id);
                    table.CheckConstraint("CK_CallSessions_DifferentUsers", "[CallerUserId] <> [CalleeUserId]");
                    table.CheckConstraint("CK_CallSessions_EndReason", "[EndReason] IS NULL OR [EndReason] IN ('Ended','Rejected','Cancelled','Busy','CallsDisabled','Failed')");
                    table.CheckConstraint("CK_CallSessions_Kind", "[Kind] IN ('Voice','Video')");
                    table.CheckConstraint("CK_CallSessions_Status", "[Status] IN ('Ringing','Active','Ended')");
                    table.CheckConstraint("CK_CallSessions_TimeOrder", "([AnsweredAtUtc] IS NULL OR [AnsweredAtUtc] >= [StartedAtUtc]) AND ([EndedAtUtc] IS NULL OR [EndedAtUtc] >= [StartedAtUtc]) AND ([AnsweredAtUtc] IS NULL OR [EndedAtUtc] IS NULL OR [EndedAtUtc] >= [AnsweredAtUtc])");
                    table.ForeignKey(
                        name: "FK_CallSessions_Conversations_ConversationId",
                        column: x => x.ConversationId,
                        principalSchema: "communication",
                        principalTable: "Conversations",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "ConversationParticipants",
                schema: "communication",
                columns: table => new
                {
                    ConversationId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    UserId = table.Column<string>(type: "nvarchar(128)", maxLength: 128, nullable: false),
                    Role = table.Column<string>(type: "varchar(16)", unicode: false, maxLength: 16, nullable: false),
                    JoinedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    LeftAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_ConversationParticipants", x => new { x.ConversationId, x.UserId });
                    table.CheckConstraint("CK_ConversationParticipants_MembershipTime", "[LeftAtUtc] IS NULL OR [LeftAtUtc] >= [JoinedAtUtc]");
                    table.CheckConstraint("CK_ConversationParticipants_Role", "[Role] IN ('Member','Admin','Owner')");
                    table.ForeignKey(
                        name: "FK_ConversationParticipants_Conversations_ConversationId",
                        column: x => x.ConversationId,
                        principalSchema: "communication",
                        principalTable: "Conversations",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "Messages",
                schema: "communication",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    ConversationId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    SenderUserId = table.Column<string>(type: "nvarchar(128)", maxLength: 128, nullable: false),
                    ClientMessageId = table.Column<string>(type: "nvarchar(100)", maxLength: 100, nullable: false),
                    SequenceNumber = table.Column<long>(type: "bigint", nullable: false)
                        .Annotation("SqlServer:Identity", "1, 1"),
                    Kind = table.Column<string>(type: "varchar(16)", unicode: false, maxLength: 16, nullable: false),
                    TextContent = table.Column<string>(type: "nvarchar(4000)", maxLength: 4000, nullable: true),
                    Url = table.Column<string>(type: "nvarchar(2048)", maxLength: 2048, nullable: true),
                    CreatedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Messages", x => x.Id);
                    table.CheckConstraint("CK_Messages_Content", "([Kind] = 'Text' AND [TextContent] IS NOT NULL AND [Url] IS NULL) OR ([Kind] = 'Link' AND [Url] IS NOT NULL) OR ([Kind] IN ('Image','Video','Voice','File'))");
                    table.CheckConstraint("CK_Messages_Kind", "[Kind] IN ('Text','Link','Image','Video','Voice','File')");
                    table.ForeignKey(
                        name: "FK_Messages_Conversations_ConversationId",
                        column: x => x.ConversationId,
                        principalSchema: "communication",
                        principalTable: "Conversations",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "MessageReceipts",
                schema: "communication",
                columns: table => new
                {
                    MessageId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    UserId = table.Column<string>(type: "nvarchar(128)", maxLength: 128, nullable: false),
                    DeliveredAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    ReadAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_MessageReceipts", x => new { x.MessageId, x.UserId });
                    table.CheckConstraint("CK_MessageReceipts_ReadRequiresDelivery", "[ReadAtUtc] IS NULL OR [DeliveredAtUtc] IS NOT NULL");
                    table.CheckConstraint("CK_MessageReceipts_TimeOrder", "[ReadAtUtc] IS NULL OR [ReadAtUtc] >= [DeliveredAtUtc]");
                    table.ForeignKey(
                        name: "FK_MessageReceipts_Messages_MessageId",
                        column: x => x.MessageId,
                        principalSchema: "communication",
                        principalTable: "Messages",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "IX_CallSessions_Callee_StartedAtUtc",
                schema: "communication",
                table: "CallSessions",
                columns: new[] { "CalleeUserId", "StartedAtUtc" });

            migrationBuilder.CreateIndex(
                name: "IX_CallSessions_Conversation_StartedAtUtc",
                schema: "communication",
                table: "CallSessions",
                columns: new[] { "ConversationId", "StartedAtUtc" });

            migrationBuilder.CreateIndex(
                name: "UX_CallSessions_Caller_ClientCallId",
                schema: "communication",
                table: "CallSessions",
                columns: new[] { "CallerUserId", "ClientCallId" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_ConversationParticipants_ActiveUser",
                schema: "communication",
                table: "ConversationParticipants",
                column: "UserId",
                filter: "[LeftAtUtc] IS NULL");

            migrationBuilder.CreateIndex(
                name: "IX_Conversations_LastActivityAtUtc",
                schema: "communication",
                table: "Conversations",
                column: "LastActivityAtUtc");

            migrationBuilder.CreateIndex(
                name: "UX_Conversations_DirectKey",
                schema: "communication",
                table: "Conversations",
                column: "DirectKey",
                unique: true,
                filter: "[DirectKey] IS NOT NULL");

            migrationBuilder.CreateIndex(
                name: "IX_MessageReceipts_User_ReadAtUtc",
                schema: "communication",
                table: "MessageReceipts",
                columns: new[] { "UserId", "ReadAtUtc" });

            migrationBuilder.CreateIndex(
                name: "IX_Messages_Conversation_CreatedAtUtc",
                schema: "communication",
                table: "Messages",
                columns: new[] { "ConversationId", "CreatedAtUtc" });

            migrationBuilder.CreateIndex(
                name: "UX_Messages_Conversation_Sequence",
                schema: "communication",
                table: "Messages",
                columns: new[] { "ConversationId", "SequenceNumber" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "UX_Messages_Sender_ClientMessageId",
                schema: "communication",
                table: "Messages",
                columns: new[] { "SenderUserId", "ClientMessageId" },
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "CallSessions",
                schema: "communication");

            migrationBuilder.DropTable(
                name: "ConversationParticipants",
                schema: "communication");

            migrationBuilder.DropTable(
                name: "MessageReceipts",
                schema: "communication");

            migrationBuilder.DropTable(
                name: "UserCommunicationPreferences",
                schema: "communication");

            migrationBuilder.DropTable(
                name: "Messages",
                schema: "communication");

            migrationBuilder.DropTable(
                name: "Conversations",
                schema: "communication");
        }
    }
}
