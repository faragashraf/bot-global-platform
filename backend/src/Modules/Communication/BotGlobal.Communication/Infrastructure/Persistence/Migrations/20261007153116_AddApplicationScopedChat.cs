using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace BotGlobal.Communication.Infrastructure.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class AddApplicationScopedChat : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            if (ActiveProvider != "Npgsql.EntityFrameworkCore.PostgreSQL")
                throw new NotSupportedException("Chat activation targets PostgreSQL only. Use the reviewed chat-only SQL artifact; do not apply the legacy migration chain.");

            migrationBuilder.EnsureSchema("communication");
            migrationBuilder.CreateTable(
                name: "ChatConversations",
                schema: "communication",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    ApplicationId = table.Column<Guid>(type: "uuid", nullable: false),
                    DirectPairKey = table.Column<string>(type: "character varying(420)", maxLength: 420, nullable: false, collation: "C"),
                    FirstSubjectId = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false, collation: "C"),
                    SecondSubjectId = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false, collation: "C"),
                    NextSequence = table.Column<long>(type: "bigint", nullable: false),
                    Version = table.Column<long>(type: "bigint", nullable: false),
                    CreatedAtUtc = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    UpdatedAtUtc = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_ChatConversations", x => x.Id);
                    table.UniqueConstraint("AK_ChatConversations_ApplicationId_Id", x => new { x.ApplicationId, x.Id });
                });

            migrationBuilder.CreateTable(
                name: "ChatMessages",
                schema: "communication",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    ApplicationId = table.Column<Guid>(type: "uuid", nullable: false),
                    ConversationId = table.Column<Guid>(type: "uuid", nullable: false),
                    Sequence = table.Column<long>(type: "bigint", nullable: false),
                    SenderSubjectId = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false, collation: "C"),
                    ClientMessageId = table.Column<string>(type: "character varying(100)", maxLength: 100, nullable: false, collation: "C"),
                    Kind = table.Column<int>(type: "integer", nullable: false),
                    PayloadFingerprint = table.Column<string>(type: "character varying(64)", unicode: false, maxLength: 64, nullable: false),
                    Text = table.Column<string>(type: "character varying(4000)", maxLength: 4000, nullable: true),
                    VoiceTransferId = table.Column<Guid>(type: "uuid", nullable: true),
                    CreatedAtUtc = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_ChatMessages", x => x.Id);
                    table.UniqueConstraint("AK_ChatMessages_ApplicationId_Id", x => new { x.ApplicationId, x.Id });
                    table.ForeignKey(
                        name: "FK_ChatMessages_ChatConversations_ApplicationId_ConversationId",
                        columns: x => new { x.ApplicationId, x.ConversationId },
                        principalSchema: "communication",
                        principalTable: "ChatConversations",
                        principalColumns: new[] { "ApplicationId", "Id" },
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "ChatReceipts",
                schema: "communication",
                columns: table => new
                {
                    ApplicationId = table.Column<Guid>(type: "uuid", nullable: false),
                    ConversationId = table.Column<Guid>(type: "uuid", nullable: false),
                    SubjectId = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false, collation: "C"),
                    LastReadSequence = table.Column<long>(type: "bigint", nullable: false),
                    UpdatedAtUtc = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_ChatReceipts", x => new { x.ApplicationId, x.ConversationId, x.SubjectId });
                    table.ForeignKey(
                        name: "FK_ChatReceipts_ChatConversations_ApplicationId_ConversationId",
                        columns: x => new { x.ApplicationId, x.ConversationId },
                        principalSchema: "communication",
                        principalTable: "ChatConversations",
                        principalColumns: new[] { "ApplicationId", "Id" },
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "ChatDispatches",
                schema: "communication",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    ApplicationId = table.Column<Guid>(type: "uuid", nullable: false),
                    MessageId = table.Column<Guid>(type: "uuid", nullable: false),
                    LeaseId = table.Column<Guid>(type: "uuid", nullable: true),
                    RouteOutcomes = table.Column<string>(type: "character varying(16000)", maxLength: 16000, nullable: false),
                    HintsSent = table.Column<bool>(type: "boolean", nullable: false),
                    RecipientSubjectId = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false, collation: "C"),
                    State = table.Column<int>(type: "integer", nullable: false),
                    AttemptCount = table.Column<int>(type: "integer", nullable: false),
                    NextAttemptAtUtc = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    SafeErrorCode = table.Column<string>(type: "character varying(120)", unicode: false, maxLength: 120, nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_ChatDispatches", x => x.Id);
                    table.ForeignKey(
                        name: "FK_ChatDispatches_ChatMessages_ApplicationId_MessageId",
                        columns: x => new { x.ApplicationId, x.MessageId },
                        principalSchema: "communication",
                        principalTable: "ChatMessages",
                        principalColumns: new[] { "ApplicationId", "Id" },
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "ChatVoiceTransfers",
                schema: "communication",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    ApplicationId = table.Column<Guid>(type: "uuid", nullable: false),
                    ConversationId = table.Column<Guid>(type: "uuid", nullable: false),
                    MessageId = table.Column<Guid>(type: "uuid", nullable: true),
                    SenderSubjectId = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false, collation: "C"),
                    RecipientSubjectId = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false, collation: "C"),
                    FileKey = table.Column<string>(type: "character varying(160)", maxLength: 160, nullable: false, collation: "C"),
                    Sha256 = table.Column<string>(type: "character varying(64)", unicode: false, maxLength: 64, nullable: false),
                    Length = table.Column<long>(type: "bigint", nullable: false),
                    DurationMilliseconds = table.Column<int>(type: "integer", nullable: false),
                    State = table.Column<int>(type: "integer", nullable: false),
                    PublishedAtUtc = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    ExpiresAtUtc = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    AcknowledgedAtUtc = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    AcknowledgedInstallationId = table.Column<Guid>(type: "uuid", nullable: true),
                    DeletedAtUtc = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    DeleteAttempts = table.Column<int>(type: "integer", nullable: false),
                    SafeDeleteError = table.Column<string>(type: "character varying(120)", unicode: false, maxLength: 120, nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_ChatVoiceTransfers", x => x.Id);
                    table.UniqueConstraint("AK_ChatVoiceTransfers_ApplicationId_Id", x => new { x.ApplicationId, x.Id });
                    table.ForeignKey(
                        name: "FK_ChatVoiceTransfers_ChatConversations_App_Conversation",
                        columns: x => new { x.ApplicationId, x.ConversationId },
                        principalSchema: "communication",
                        principalTable: "ChatConversations",
                        principalColumns: new[] { "ApplicationId", "Id" },
                        onDelete: ReferentialAction.Restrict);
                    table.ForeignKey(
                        name: "FK_ChatVoiceTransfers_ChatMessages_ApplicationId_MessageId",
                        columns: x => new { x.ApplicationId, x.MessageId },
                        principalSchema: "communication",
                        principalTable: "ChatMessages",
                        principalColumns: new[] { "ApplicationId", "Id" },
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateIndex(
                name: "IX_ChatConversations_ApplicationId_DirectPairKey",
                schema: "communication",
                table: "ChatConversations",
                columns: new[] { "ApplicationId", "DirectPairKey" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_ChatConversations_ApplicationId_FirstSubjectId_UpdatedAtUtc",
                schema: "communication",
                table: "ChatConversations",
                columns: new[] { "ApplicationId", "FirstSubjectId", "UpdatedAtUtc" });

            migrationBuilder.CreateIndex(
                name: "IX_ChatConversations_ApplicationId_SecondSubjectId_UpdatedAtUtc",
                schema: "communication",
                table: "ChatConversations",
                columns: new[] { "ApplicationId", "SecondSubjectId", "UpdatedAtUtc" });

            migrationBuilder.CreateIndex(
                name: "IX_ChatDispatches_ApplicationId_MessageId",
                schema: "communication",
                table: "ChatDispatches",
                columns: new[] { "ApplicationId", "MessageId" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_ChatDispatches_State_NextAttemptAtUtc",
                schema: "communication",
                table: "ChatDispatches",
                columns: new[] { "State", "NextAttemptAtUtc" });

            migrationBuilder.CreateIndex(
                name: "IX_ChatMessages_ApplicationId_ConversationId_Sequence",
                schema: "communication",
                table: "ChatMessages",
                columns: new[] { "ApplicationId", "ConversationId", "Sequence" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_ChatMessages_ApplicationId_SenderSubjectId_ClientMessageId",
                schema: "communication",
                table: "ChatMessages",
                columns: new[] { "ApplicationId", "SenderSubjectId", "ClientMessageId" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_ChatVoiceTransfers_ApplicationId_ConversationId",
                schema: "communication",
                table: "ChatVoiceTransfers",
                columns: new[] { "ApplicationId", "ConversationId" });

            migrationBuilder.CreateIndex(
                name: "IX_ChatVoiceTransfers_ApplicationId_FileKey",
                schema: "communication",
                table: "ChatVoiceTransfers",
                columns: new[] { "ApplicationId", "FileKey" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_ChatVoiceTransfers_ApplicationId_MessageId",
                schema: "communication",
                table: "ChatVoiceTransfers",
                columns: new[] { "ApplicationId", "MessageId" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_ChatVoiceTransfers_State_ExpiresAtUtc",
                schema: "communication",
                table: "ChatVoiceTransfers",
                columns: new[] { "State", "ExpiresAtUtc" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            throw new NotSupportedException("Chat rollback preserves records. Disable workers and ingress, then use the approved backup/recovery procedure; automatic data deletion is prohibited.");
        }
    }
}
