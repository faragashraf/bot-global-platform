using BotGlobal.Communication.Domain.Identity;
using BotGlobal.Communication.Domain.Calls;
using BotGlobal.Communication.Domain.Conversations;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace BotGlobal.Communication.Infrastructure.Persistence.Configurations;

public sealed class CallSessionConfiguration
    : IEntityTypeConfiguration<CallSession>
{
    public void Configure(
        EntityTypeBuilder<CallSession> builder)
    {
        builder.ToTable(
            "CallSessions",
            "communication",
            table =>
            {
                table.HasCheckConstraint(
                    "CK_CallSessions_Kind",
                    "[Kind] IN ('Voice','Video')");

                table.HasCheckConstraint(
                    "CK_CallSessions_Status",
                    "[Status] IN ('Ringing','Active','Ended')");

                table.HasCheckConstraint(
                    "CK_CallSessions_EndReason",
                    "[EndReason] IS NULL OR [EndReason] IN "
                    + "('Ended','Rejected','Cancelled','Busy','CallsDisabled','Failed')");

                table.HasCheckConstraint(
                    "CK_CallSessions_DifferentUsers",
                    "[CallerUserId] <> [CalleeUserId]");

                table.HasCheckConstraint(
                    "CK_CallSessions_TimeOrder",
                    "([AnsweredAtUtc] IS NULL OR [AnsweredAtUtc] >= [StartedAtUtc]) "
                    + "AND ([EndedAtUtc] IS NULL OR [EndedAtUtc] >= [StartedAtUtc]) "
                    + "AND ([AnsweredAtUtc] IS NULL OR [EndedAtUtc] IS NULL "
                    + "OR [EndedAtUtc] >= [AnsweredAtUtc])");
            });

        builder.HasKey(call => call.Id);

        builder.Property(call => call.ClientCallId)
            .HasMaxLength(CallSession.ClientCallIdMaxLength)
            .IsRequired();

        builder.Property(call => call.Kind)
            .HasConversion<string>()
            .HasMaxLength(16)
            .IsUnicode(false)
            .IsRequired();

        builder.Property(call => call.Status)
            .HasConversion<string>()
            .HasMaxLength(16)
            .IsUnicode(false)
            .IsRequired();

        builder.Property(call => call.EndReason)
            .HasConversion<string>()
            .HasMaxLength(24)
            .IsUnicode(false);

        builder.Property(call => call.StartedAtUtc)
            .HasColumnType("datetimeoffset")
            .IsRequired();

        builder.Property(call => call.AnsweredAtUtc)
            .HasColumnType("datetimeoffset");

        builder.Property(call => call.EndedAtUtc)
            .HasColumnType("datetimeoffset");

        builder.HasIndex(
                call => new
                {
                    call.CallerUserId,
                    call.ClientCallId
                })
            .IsUnique()
            .HasDatabaseName(
                "UX_CallSessions_Caller_ClientCallId");

        builder.HasIndex(
                call => new
                {
                    call.CalleeUserId,
                    call.StartedAtUtc
                })
            .HasDatabaseName(
                "IX_CallSessions_Callee_StartedAtUtc");

        builder.HasIndex(
                call => new
                {
                    call.ConversationId,
                    call.StartedAtUtc
                })
            .HasDatabaseName(
                "IX_CallSessions_Conversation_StartedAtUtc");

        builder.HasOne<Conversation>()
            .WithMany()
            .HasForeignKey(call => call.ConversationId)
            .OnDelete(DeleteBehavior.Restrict);
        builder.Property(call => call.CallerUserId)
            .HasMaxLength(ExternalUserId.MaxLength)
            .IsRequired();

        builder.Property(call => call.CalleeUserId)
            .HasMaxLength(ExternalUserId.MaxLength)
            .IsRequired();

    }
}
