using BotGlobal.Communication.Domain.Identity;
using BotGlobal.Communication.Domain.Conversations;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace BotGlobal.Communication.Infrastructure.Persistence.Configurations;

public sealed class ConversationConfiguration
    : IEntityTypeConfiguration<Conversation>
{
    public void Configure(
        EntityTypeBuilder<Conversation> builder)
    {
        builder.ToTable(
            "Conversations",
            "communication",
            table =>
            {
                table.HasCheckConstraint(
                    "CK_Conversations_Type",
                    "[Type] IN ('Direct','Group')");

                table.HasCheckConstraint(
                    "CK_Conversations_Shape",
                    "([Type] = 'Direct' AND [DirectKey] IS NOT NULL AND [Title] IS NULL) "
                    + "OR ([Type] = 'Group' AND [DirectKey] IS NULL AND [Title] IS NOT NULL)");

                table.HasCheckConstraint(
                    "CK_Conversations_ActivityTime",
                    "[LastActivityAtUtc] >= [CreatedAtUtc]");
            });

        builder.HasKey(conversation => conversation.Id);

        builder.Property(conversation => conversation.Type)
            .HasConversion<string>()
            .HasMaxLength(16)
            .IsUnicode(false)
            .IsRequired();

        builder.Property(conversation => conversation.Title)
            .HasMaxLength(Conversation.GroupTitleMaxLength);

        builder.Property(conversation => conversation.DirectKey)
            .HasMaxLength(Conversation.DirectKeyMaxLength)
            .IsUnicode(false);

        builder.Property(conversation => conversation.CreatedAtUtc)
            .HasColumnType("datetimeoffset")
            .IsRequired();

        builder.Property(conversation => conversation.LastActivityAtUtc)
            .HasColumnType("datetimeoffset")
            .IsRequired();

        builder.HasIndex(conversation => conversation.DirectKey)
            .IsUnique()
            .HasFilter("[DirectKey] IS NOT NULL")
            .HasDatabaseName("UX_Conversations_DirectKey");

        builder.HasIndex(conversation => conversation.LastActivityAtUtc)
            .HasDatabaseName("IX_Conversations_LastActivityAtUtc");

        builder.HasMany(conversation => conversation.Participants)
            .WithOne()
            .HasForeignKey(participant => participant.ConversationId)
            .OnDelete(DeleteBehavior.Cascade);

        builder.Navigation(conversation => conversation.Participants)
            .UsePropertyAccessMode(PropertyAccessMode.Field);
        builder.Property(conversation => conversation.CreatedByUserId)
            .HasMaxLength(ExternalUserId.MaxLength)
            .IsRequired();

    }
}
