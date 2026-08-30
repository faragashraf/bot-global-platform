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
                    "[Status] BETWEEN 1 AND 7");
                table.HasCheckConstraint(
                    "CK_NotificationRecipients_AttemptCount",
                    "[AttemptCount] >= 0");
                table.HasCheckConstraint(
                    "CK_NotificationRecipients_Lease",
                    "([LeaseId] IS NULL AND [LeaseExpiresAtUtc] IS NULL) OR ([LeaseId] IS NOT NULL AND [LeaseExpiresAtUtc] IS NOT NULL)");
                table.HasCheckConstraint(
                    "CK_NotificationRecipients_NextAttempt",
                    "([Status] IN (1, 2) AND [NextAttemptAtUtc] IS NOT NULL) OR ([Status] IN (3, 4, 5, 6, 7) AND [NextAttemptAtUtc] IS NULL)");
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
