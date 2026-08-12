using BotGlobal.Communication.Domain.Identity;
using BotGlobal.Communication.Domain.Conversations;
using BotGlobal.Communication.Domain.Messaging;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace BotGlobal.Communication.Infrastructure.Persistence.Configurations;

public sealed class MessageConfiguration
    : IEntityTypeConfiguration<Message>
{
    public void Configure(
        EntityTypeBuilder<Message> builder)
    {
        builder.ToTable(
            "Messages",
            "communication",
            table =>
            {
                table.HasCheckConstraint(
                    "CK_Messages_Kind",
                    "[Kind] IN ('Text','Link','Image','Video','Voice','File')");

                table.HasCheckConstraint(
                    "CK_Messages_Content",
                    "([Kind] = 'Text' AND [TextContent] IS NOT NULL AND [Url] IS NULL) "
                    + "OR ([Kind] = 'Link' AND [Url] IS NOT NULL) "
                    + "OR ([Kind] IN ('Image','Video','Voice','File'))");
            });

        builder.HasKey(message => message.Id);

        builder.Property(message => message.ClientMessageId)
            .HasMaxLength(Message.ClientMessageIdMaxLength)
            .IsRequired();

        builder.Property(message => message.SequenceNumber)
            .UseIdentityColumn();

        builder.Property(message => message.Kind)
            .HasConversion<string>()
            .HasMaxLength(16)
            .IsUnicode(false)
            .IsRequired();

        builder.Property(message => message.TextContent)
            .HasMaxLength(Message.TextMaxLength);

        builder.Property(message => message.Url)
            .HasMaxLength(Message.UrlMaxLength);

        builder.Property(message => message.CreatedAtUtc)
            .HasColumnType("datetimeoffset")
            .IsRequired();

        builder.HasIndex(
                message => new
                {
                    message.ConversationId,
                    message.SequenceNumber
                })
            .IsUnique()
            .HasDatabaseName(
                "UX_Messages_Conversation_Sequence");

        builder.HasIndex(
                message => new
                {
                    message.SenderUserId,
                    message.ClientMessageId
                })
            .IsUnique()
            .HasDatabaseName(
                "UX_Messages_Sender_ClientMessageId");

        builder.HasIndex(
                message => new
                {
                    message.ConversationId,
                    message.CreatedAtUtc
                })
            .HasDatabaseName(
                "IX_Messages_Conversation_CreatedAtUtc");

        builder.HasOne<Conversation>()
            .WithMany()
            .HasForeignKey(message => message.ConversationId)
            .OnDelete(DeleteBehavior.Restrict);

        builder.HasMany(message => message.Receipts)
            .WithOne()
            .HasForeignKey(receipt => receipt.MessageId)
            .OnDelete(DeleteBehavior.Cascade);

        builder.Navigation(message => message.Receipts)
            .UsePropertyAccessMode(PropertyAccessMode.Field);
        builder.Property(message => message.SenderUserId)
            .HasMaxLength(ExternalUserId.MaxLength)
            .IsRequired();

    }
}
