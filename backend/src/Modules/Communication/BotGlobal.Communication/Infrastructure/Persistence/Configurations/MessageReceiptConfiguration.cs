using BotGlobal.Communication.Domain.Identity;
using BotGlobal.Communication.Domain.Messaging;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace BotGlobal.Communication.Infrastructure.Persistence.Configurations;

public sealed class MessageReceiptConfiguration
    : IEntityTypeConfiguration<MessageReceipt>
{
    public void Configure(
        EntityTypeBuilder<MessageReceipt> builder)
    {
        builder.ToTable(
            "MessageReceipts",
            "communication",
            table =>
            {
                table.HasCheckConstraint(
                    "CK_MessageReceipts_ReadRequiresDelivery",
                    "[ReadAtUtc] IS NULL OR [DeliveredAtUtc] IS NOT NULL");

                table.HasCheckConstraint(
                    "CK_MessageReceipts_TimeOrder",
                    "[ReadAtUtc] IS NULL OR [ReadAtUtc] >= [DeliveredAtUtc]");
            });

        builder.HasKey(
            receipt => new
            {
                receipt.MessageId,
                receipt.UserId
            });

        builder.Property(receipt => receipt.DeliveredAtUtc)
            .HasColumnType("datetimeoffset");

        builder.Property(receipt => receipt.ReadAtUtc)
            .HasColumnType("datetimeoffset");

        builder.HasIndex(
                receipt => new
                {
                    receipt.UserId,
                    receipt.ReadAtUtc
                })
            .HasDatabaseName(
                "IX_MessageReceipts_User_ReadAtUtc");
        builder.Property(receipt => receipt.UserId)
            .HasMaxLength(ExternalUserId.MaxLength)
            .IsRequired();

    }
}
