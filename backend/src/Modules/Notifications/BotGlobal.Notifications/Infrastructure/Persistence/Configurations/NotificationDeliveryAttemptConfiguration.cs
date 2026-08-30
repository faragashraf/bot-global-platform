using BotGlobal.Notifications.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace BotGlobal.Notifications.Infrastructure.Persistence.Configurations;

internal sealed class NotificationDeliveryAttemptConfiguration
    : IEntityTypeConfiguration<NotificationDeliveryAttempt>
{
    public void Configure(
        EntityTypeBuilder<NotificationDeliveryAttempt> builder)
    {
        builder.ToTable(
            "NotificationDeliveryAttempts",
            NotificationsModule.DatabaseSchema,
            table =>
            {
                table.HasCheckConstraint(
                    "CK_NotificationDeliveryAttempts_Status",
                    "[Status] BETWEEN 1 AND 9");
                table.HasCheckConstraint(
                    "CK_NotificationDeliveryAttempts_AttemptNumber",
                    "[AttemptNumber] >= 1");
                table.HasCheckConstraint(
                    "CK_NotificationDeliveryAttempts_Completion",
                    "([Status] IN (1, 2) AND [CompletedAtUtc] IS NULL) OR ([Status] BETWEEN 3 AND 9 AND [CompletedAtUtc] IS NOT NULL)");
                table.HasCheckConstraint(
                    "CK_NotificationDeliveryAttempts_Invocation",
                    "([Status] = 1 AND [ProviderInvocationStartedAtUtc] IS NULL) OR ([Status] BETWEEN 2 AND 8 AND [ProviderInvocationStartedAtUtc] IS NOT NULL) OR ([Status] = 9)");
            });

        builder.HasKey(attempt => attempt.Id);

        builder.Property(attempt => attempt.DeliveryKey)
            .HasMaxLength(100)
            .IsUnicode(false)
            .IsRequired();

        builder.Property(attempt => attempt.Transport)
            .HasMaxLength(32)
            .IsUnicode(false);

        builder.Property(attempt => attempt.ProviderMessageId)
            .HasMaxLength(500)
            .IsUnicode(false);

        builder.Property(attempt => attempt.SafeErrorCode)
            .HasMaxLength(100)
            .IsUnicode(false);

        builder.Property(attempt => attempt.RowVersion)
            .IsRowVersion();

        builder.HasIndex(attempt => new
        {
            attempt.NotificationRecipientId,
            attempt.AttemptNumber
        })
            .IsUnique()
            .HasDatabaseName(
                "UX_NotificationDeliveryAttempts_Recipient_Number");

        builder.HasIndex(attempt => new
        {
            attempt.ApplicationId,
            attempt.CampaignId,
            attempt.DeliveryKey
        })
            .HasDatabaseName(
                "IX_NotificationDeliveryAttempts_Application_Campaign_Delivery");

        builder.HasIndex(attempt => new
        {
            attempt.Status,
            attempt.CompletedAtUtc
        })
            .HasDatabaseName(
                "IX_NotificationDeliveryAttempts_Recovery");

        builder.HasOne(attempt => attempt.Recipient)
            .WithMany(recipient => recipient.DeliveryAttempts)
            .HasForeignKey(attempt => attempt.NotificationRecipientId)
            .OnDelete(DeleteBehavior.Cascade);
    }
}
