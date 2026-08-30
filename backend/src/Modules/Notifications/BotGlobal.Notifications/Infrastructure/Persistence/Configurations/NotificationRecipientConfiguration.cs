using BotGlobal.Notifications.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace BotGlobal.Notifications.Infrastructure.Persistence.Configurations;

internal sealed class NotificationRecipientConfiguration
    : IEntityTypeConfiguration<NotificationRecipient>
{
    public void Configure(EntityTypeBuilder<NotificationRecipient> builder)
    {
        builder.ToTable(
            "NotificationRecipients",
            NotificationsModule.DatabaseSchema,
            table =>
            {
                table.HasCheckConstraint(
                    "CK_NotificationRecipients_Status",
                    "[Status] BETWEEN 1 AND 10");
                table.HasCheckConstraint(
                    "CK_NotificationRecipients_AttemptCount",
                    "[AttemptCount] >= 0");
                table.HasCheckConstraint(
                    "CK_NotificationRecipients_Lease",
                    "([LeaseId] IS NULL AND [LeaseExpiresAtUtc] IS NULL) OR ([LeaseId] IS NOT NULL AND [LeaseExpiresAtUtc] IS NOT NULL)");
                table.HasCheckConstraint(
                    "CK_NotificationRecipients_NextAttempt",
                    "([Status] IN (1, 2) AND [NextAttemptAtUtc] IS NOT NULL) OR ([Status] BETWEEN 3 AND 10 AND [NextAttemptAtUtc] IS NULL)");
                table.HasCheckConstraint(
                    "CK_NotificationRecipients_CurrentAttempt",
                    "[Status] IN (1, 2, 7, 10) OR [CurrentAttemptId] IS NOT NULL");
            });

        builder.HasKey(recipient => recipient.Id);

        builder.Property(recipient => recipient.InstallationIdSnapshot)
            .HasMaxLength(200)
            .IsRequired();

        builder.Property(recipient => recipient.PlatformSnapshot)
            .HasMaxLength(50)
            .IsRequired();

        builder.Property(recipient => recipient.DeviceNameSnapshot)
            .HasMaxLength(250);

        builder.Property(recipient => recipient.DeliveryKey)
            .HasMaxLength(100)
            .IsUnicode(false)
            .IsRequired();

        builder.Property(recipient => recipient.LastTransport)
            .HasMaxLength(32)
            .IsUnicode(false);

        builder.Property(recipient => recipient.LastSafeErrorCode)
            .HasMaxLength(100)
            .IsUnicode(false);

        builder.Property(recipient => recipient.RowVersion)
            .IsRowVersion();

        builder.HasIndex(recipient => new
        {
            recipient.CampaignId,
            recipient.MobileDeviceId
        })
            .IsUnique()
            .HasDatabaseName(
                "UX_NotificationRecipients_Campaign_Device");

        builder.HasIndex(recipient => recipient.DeliveryKey)
            .IsUnique()
            .HasDatabaseName(
                "UX_NotificationRecipients_DeliveryKey");

        builder.HasIndex(recipient => recipient.CurrentAttemptId)
            .IsUnique()
            .HasFilter("[CurrentAttemptId] IS NOT NULL")
            .HasDatabaseName(
                "UX_NotificationRecipients_CurrentAttempt");

        builder.HasIndex(recipient => new
        {
            recipient.Status,
            recipient.NextAttemptAtUtc,
            recipient.LeaseExpiresAtUtc
        })
            .HasDatabaseName(
                "IX_NotificationRecipients_DispatchWork");

        builder.HasOne(recipient => recipient.Campaign)
            .WithMany()
            .HasForeignKey(recipient => recipient.CampaignId)
            .OnDelete(DeleteBehavior.Cascade);
    }
}
