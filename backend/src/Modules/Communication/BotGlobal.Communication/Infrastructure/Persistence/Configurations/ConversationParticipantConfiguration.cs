using BotGlobal.Communication.Domain.Identity;
using BotGlobal.Communication.Domain.Conversations;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace BotGlobal.Communication.Infrastructure.Persistence.Configurations;

public sealed class ConversationParticipantConfiguration
    : IEntityTypeConfiguration<ConversationParticipant>
{
    public void Configure(
        EntityTypeBuilder<ConversationParticipant> builder)
    {
        builder.ToTable(
            "ConversationParticipants",
            "communication",
            table =>
            {
                table.HasCheckConstraint(
                    "CK_ConversationParticipants_Role",
                    "[Role] IN ('Member','Admin','Owner')");

                table.HasCheckConstraint(
                    "CK_ConversationParticipants_MembershipTime",
                    "[LeftAtUtc] IS NULL OR [LeftAtUtc] >= [JoinedAtUtc]");
            });

        builder.HasKey(
            participant => new
            {
                participant.ConversationId,
                participant.UserId
            });

        builder.Property(participant => participant.Role)
            .HasConversion<string>()
            .HasMaxLength(16)
            .IsUnicode(false)
            .IsRequired();

        builder.Property(participant => participant.JoinedAtUtc)
            .HasColumnType("datetimeoffset")
            .IsRequired();

        builder.Property(participant => participant.LeftAtUtc)
            .HasColumnType("datetimeoffset");

        builder.Ignore(participant => participant.IsActive);

        builder.HasIndex(participant => participant.UserId)
            .HasFilter("[LeftAtUtc] IS NULL")
            .HasDatabaseName("IX_ConversationParticipants_ActiveUser");
        builder.Property(participant => participant.UserId)
            .HasMaxLength(ExternalUserId.MaxLength)
            .IsRequired();

    }
}
