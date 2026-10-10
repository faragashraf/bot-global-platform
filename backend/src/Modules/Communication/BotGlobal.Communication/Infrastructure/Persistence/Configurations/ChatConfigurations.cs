using BotGlobal.Communication.Domain.Chat;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace BotGlobal.Communication.Infrastructure.Persistence.Configurations;

internal static class ChatDatabase
{
    public const string BinaryCollation = "C";
    public static PropertyBuilder<string> Opaque(this PropertyBuilder<string> property, int length) =>
        property.HasMaxLength(length).UseCollation(BinaryCollation).IsUnicode();
}

internal sealed class ChatConversationConfiguration : IEntityTypeConfiguration<ChatConversation>
{
    public void Configure(EntityTypeBuilder<ChatConversation> b)
    {
        b.ToTable("ChatConversations"); b.HasKey(x => x.Id); b.HasAlternateKey(x => new { x.ApplicationId, x.Id });
        b.Property(x => x.DirectPairKey).Opaque(ChatLimits.PairKey); b.Property(x => x.FirstSubjectId).Opaque(ChatLimits.Subject); b.Property(x => x.SecondSubjectId).Opaque(ChatLimits.Subject);
        b.Property(x => x.Version).IsConcurrencyToken();
        b.HasIndex(x => new { x.ApplicationId, x.DirectPairKey }).IsUnique();
        b.HasIndex(x => new { x.ApplicationId, x.FirstSubjectId, x.UpdatedAtUtc });
        b.HasIndex(x => new { x.ApplicationId, x.SecondSubjectId, x.UpdatedAtUtc });
    }
}

internal sealed class ChatMessageConfiguration : IEntityTypeConfiguration<ChatMessage>
{
    public void Configure(EntityTypeBuilder<ChatMessage> b)
    {
        b.ToTable("ChatMessages"); b.HasKey(x => x.Id); b.HasAlternateKey(x => new { x.ApplicationId, x.Id });
        b.Property(x => x.SenderSubjectId).Opaque(ChatLimits.Subject); b.Property(x => x.ClientMessageId).Opaque(ChatLimits.ClientMessageId); b.Property(x => x.PayloadFingerprint).HasMaxLength(ChatLimits.Hash).IsUnicode(false); b.Property(x => x.Text).HasMaxLength(ChatLimits.Text);
        b.Property(x => x.ReplyToSenderSubjectId).HasMaxLength(ChatLimits.Subject).UseCollation(ChatDatabase.BinaryCollation).IsUnicode();
        b.Property(x => x.ReplyToText).HasMaxLength(240);
        b.Property(x => x.EditedAtUtc);
        b.Property(x => x.DeletedAtUtc);
        b.HasIndex(x => new { x.ApplicationId, x.SenderSubjectId, x.ClientMessageId }).IsUnique();
        b.HasIndex(x => new { x.ApplicationId, x.ConversationId, x.Sequence }).IsUnique();
        b.HasOne<ChatConversation>().WithMany().HasForeignKey(x => new { x.ApplicationId, x.ConversationId }).HasPrincipalKey(x => new { x.ApplicationId, x.Id }).OnDelete(DeleteBehavior.Restrict);
        b.HasOne<ChatMessage>().WithMany().HasForeignKey(x => new { x.ApplicationId, x.ReplyToMessageId })
            .HasPrincipalKey(x => new { x.ApplicationId, x.Id }).OnDelete(DeleteBehavior.Restrict);
    }
}

internal sealed class ChatVoiceTransferConfiguration : IEntityTypeConfiguration<ChatVoiceTransfer>
{
    public void Configure(EntityTypeBuilder<ChatVoiceTransfer> b)
    {
        b.ToTable("ChatVoiceTransfers"); b.HasKey(x => x.Id); b.HasAlternateKey(x => new { x.ApplicationId, x.Id });
        b.Property(x => x.SenderSubjectId).Opaque(ChatLimits.Subject); b.Property(x => x.RecipientSubjectId).Opaque(ChatLimits.Subject); b.Property(x => x.FileKey).Opaque(ChatLimits.FileKey); b.Property(x => x.Sha256).HasMaxLength(ChatLimits.Hash).IsUnicode(false); b.Property(x => x.SafeDeleteError).HasMaxLength(120).IsUnicode(false);
        b.HasIndex(x => new { x.ApplicationId, x.FileKey }).IsUnique(); b.HasIndex(x => new { x.State, x.ExpiresAtUtc });
        b.HasOne<ChatConversation>().WithMany().HasForeignKey(x => new { x.ApplicationId, x.ConversationId }).HasPrincipalKey(x => new { x.ApplicationId, x.Id }).OnDelete(DeleteBehavior.Restrict)
            .HasConstraintName("FK_ChatVoiceTransfers_ChatConversations_App_Conversation");
        b.HasOne<ChatMessage>().WithOne().HasForeignKey<ChatVoiceTransfer>(x => new { x.ApplicationId, x.MessageId })
            .HasPrincipalKey<ChatMessage>(x => new { x.ApplicationId, x.Id }).OnDelete(DeleteBehavior.Restrict);
    }
}

internal sealed class ChatReceiptConfiguration : IEntityTypeConfiguration<ChatReceipt>
{
    public void Configure(EntityTypeBuilder<ChatReceipt> b)
    {
        b.ToTable("ChatReceipts"); b.HasKey(x => new { x.ApplicationId, x.ConversationId, x.SubjectId }); b.Property(x => x.SubjectId).Opaque(ChatLimits.Subject);
        b.HasOne<ChatConversation>().WithMany().HasForeignKey(x => new { x.ApplicationId, x.ConversationId }).HasPrincipalKey(x => new { x.ApplicationId, x.Id }).OnDelete(DeleteBehavior.Restrict);
    }
}

internal sealed class ChatDispatchConfiguration : IEntityTypeConfiguration<ChatDispatch>
{
    public void Configure(EntityTypeBuilder<ChatDispatch> b)
    {
        b.ToTable("ChatDispatches"); b.HasKey(x => x.Id); b.Property(x => x.RecipientSubjectId).Opaque(ChatLimits.Subject); b.Property(x => x.SafeErrorCode).HasMaxLength(120).IsUnicode(false);
        b.Property(x => x.LeaseId).IsConcurrencyToken();
        b.Property(x => x.RouteOutcomes).HasMaxLength(16000);
        b.HasIndex(x => new { x.ApplicationId, x.MessageId }).IsUnique(); b.HasIndex(x => new { x.State, x.NextAttemptAtUtc });
        b.HasOne<ChatMessage>().WithOne().HasForeignKey<ChatDispatch>(x => new { x.ApplicationId, x.MessageId }).HasPrincipalKey<ChatMessage>(x => new { x.ApplicationId, x.Id }).OnDelete(DeleteBehavior.Restrict);
    }
}
