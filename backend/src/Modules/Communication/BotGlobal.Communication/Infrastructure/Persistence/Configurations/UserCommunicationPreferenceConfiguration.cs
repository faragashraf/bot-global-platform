using BotGlobal.Communication.Domain.Identity;
using BotGlobal.Communication.Domain.Preferences;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace BotGlobal.Communication.Infrastructure.Persistence.Configurations;

public sealed class UserCommunicationPreferenceConfiguration
    : IEntityTypeConfiguration<UserCommunicationPreference>
{
    public void Configure(
        EntityTypeBuilder<UserCommunicationPreference> builder)
    {
        builder.ToTable(
            "UserCommunicationPreferences",
            "communication");

        builder.HasKey(preference => preference.UserId);

        builder.Property(preference => preference.AllowVoiceCalls)
            .HasDefaultValue(false)
            .IsRequired();

        builder.Property(preference => preference.AllowVideoCalls)
            .HasDefaultValue(false)
            .IsRequired();

        builder.Property(preference => preference.UpdatedAtUtc)
            .HasColumnType("datetimeoffset")
            .IsRequired();
        builder.Property(preference => preference.UserId)
            .HasMaxLength(ExternalUserId.MaxLength)
            .IsRequired();

    }
}
