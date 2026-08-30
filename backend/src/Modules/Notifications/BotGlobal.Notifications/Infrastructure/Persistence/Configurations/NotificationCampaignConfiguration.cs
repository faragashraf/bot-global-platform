using BotGlobal.Notifications.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace BotGlobal.Notifications.Infrastructure.Persistence.Configurations;

internal sealed class NotificationCampaignConfiguration
    : IEntityTypeConfiguration<NotificationCampaign>
{
    public void Configure(EntityTypeBuilder<NotificationCampaign> builder)
    {
        builder.ToTable(
            "NotificationCampaigns",
            NotificationsModule.DatabaseSchema,
            table =>
            {
                table.HasCheckConstraint(
                    "CK_NotificationCampaigns_Status",
                    "[Status] BETWEEN 1 AND 8");
                table.HasCheckConstraint(
                    "CK_NotificationCampaigns_AudienceKind",
                    "[AudienceKind] = 1");
                table.HasCheckConstraint(
                    "CK_NotificationCampaigns_Priority",
                    "[Priority] IN (1, 2)");
                table.HasCheckConstraint(
                    "CK_NotificationCampaigns_Lifetime",
                    "[ExpiresAtUtc] > [CreatedAtUtc]");
                table.HasCheckConstraint(
                    "CK_NotificationCampaigns_Counts",
                    "[AudienceSubjectCount] >= 0 AND [AudienceDeviceCount] >= 0 AND [PushCapableDeviceCount] >= 0 AND [PendingCount] >= 0 AND [SignalRDispatchedCount] >= 0 AND [FcmAcceptedCount] >= 0 AND [FailedCount] >= 0 AND [SkippedCount] >= 0 AND [ExpiredCount] >= 0");
                table.HasCheckConstraint(
                    "CK_NotificationCampaigns_AudienceLease",
                    "([AudienceLeaseId] IS NULL AND [AudienceLeaseExpiresAtUtc] IS NULL) OR ([AudienceLeaseId] IS NOT NULL AND [AudienceLeaseExpiresAtUtc] IS NOT NULL)");
            });

        builder.HasKey(campaign => campaign.Id);

        builder.Property(campaign => campaign.PlatformClientKeySnapshot)
            .HasMaxLength(100)
            .IsRequired();

        builder.Property(campaign => campaign.PlatformClientDisplayNameSnapshot)
            .HasMaxLength(200)
            .IsRequired();

        builder.Property(campaign => campaign.TitleAr)
            .HasMaxLength(200)
            .IsRequired();

        builder.Property(campaign => campaign.TitleEn)
            .HasMaxLength(200)
            .IsRequired();

        builder.Property(campaign => campaign.BodyAr)
            .HasMaxLength(4000)
            .IsRequired();

        builder.Property(campaign => campaign.BodyEn)
            .HasMaxLength(4000)
            .IsRequired();

        builder.Property(campaign => campaign.Type)
            .HasMaxLength(100)
            .IsRequired();

        builder.Property(campaign => campaign.IdempotencyKey)
            .HasMaxLength(200)
            .IsUnicode(false)
            .IsRequired();

        builder.Property(campaign => campaign.RequestFingerprint)
            .HasMaxLength(64)
            .IsUnicode(false)
            .IsRequired();

        builder.Property(campaign => campaign.CreatedByDisplayNameSnapshot)
            .HasMaxLength(200)
            .IsRequired();

        builder.Property(campaign => campaign.RowVersion)
            .IsRowVersion();

        builder.HasIndex(campaign => new
            {
                campaign.CreatedByUserId,
                campaign.IdempotencyKey
            })
            .IsUnique()
            .HasDatabaseName(
                "UX_NotificationCampaigns_Admin_IdempotencyKey");

        builder.HasIndex(campaign => new
            {
                campaign.PlatformClientId,
                campaign.CreatedAtUtc
            })
            .HasDatabaseName(
                "IX_NotificationCampaigns_PlatformClient_CreatedAtUtc");

        builder.HasIndex(campaign => new
            {
                campaign.Status,
                campaign.AudienceLeaseExpiresAtUtc
            })
            .HasDatabaseName(
                "IX_NotificationCampaigns_AudienceWork");
    }
}
