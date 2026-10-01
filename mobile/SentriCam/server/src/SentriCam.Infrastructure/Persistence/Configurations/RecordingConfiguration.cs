using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using SentriCam.Domain.Devices;
using SentriCam.Domain.Recordings;

namespace SentriCam.Infrastructure.Persistence.Configurations;

internal sealed class RecordingConfiguration : IEntityTypeConfiguration<Recording>
{
    public void Configure(EntityTypeBuilder<Recording> builder)
    {
        builder.ToTable("Recordings");
        builder.HasKey(recording => recording.Id);
        builder.Property(recording => recording.Id).ValueGeneratedNever();
        builder.Property(recording => recording.DeviceId).HasDeviceIdConversion().IsRequired();
        builder.Property(recording => recording.ClientRecordingId).HasMaxLength(128).IsRequired();
        builder.Property(recording => recording.SessionId).HasMaxLength(128).IsRequired();
        builder.Property(recording => recording.OriginalFileName).HasMaxLength(255).IsRequired();
        builder.Property(recording => recording.ContentType).HasMaxLength(100).IsRequired();
        builder.Property(recording => recording.DurationMilliseconds).IsRequired();
        builder.Property(recording => recording.SizeBytes).IsRequired();
        builder.Property(recording => recording.CreatedUtc).HasPrecision(7).IsRequired();
        builder.Property(recording => recording.UploadedUtc).HasPrecision(7).IsRequired();
        builder.Property(recording => recording.IsMotion).IsRequired();
        builder.Property(recording => recording.IsManual).IsRequired();
        builder.Property(recording => recording.ChecksumSha256).HasMaxLength(64).IsRequired();
        builder.Property(recording => recording.RelativePath).HasMaxLength(1024).IsRequired();
        builder.Property(recording => recording.ThumbnailRelativePath).HasMaxLength(1024);
        builder.Property(recording => recording.ThumbnailState).IsRequired();
        builder.Property(recording => recording.ThumbnailGenerationAttempts).IsRequired();
        builder.Property(recording => recording.ThumbnailErrorCode).HasMaxLength(100);
        builder.Property(recording => recording.ThumbnailGeneratedUtc).HasPrecision(7);
        builder.Property(recording => recording.ThumbnailProcessingStartedUtc).HasPrecision(7);
        builder.Property<byte[]>("RowVersion").IsRowVersionToken();
        builder.HasOne<Device>()
            .WithMany()
            .HasForeignKey(recording => recording.DeviceId)
            .OnDelete(DeleteBehavior.Cascade);
        builder.HasIndex(recording => new { recording.DeviceId, recording.ClientRecordingId })
            .IsUnique()
            .HasDatabaseName("UX_Recordings_DeviceId_ClientRecordingId");
        builder.HasIndex(recording => new { recording.DeviceId, recording.CreatedUtc })
            .IsDescending(false, true)
            .HasDatabaseName("IX_Recordings_DeviceId_CreatedUtc");
        builder.HasIndex(recording => recording.CreatedUtc)
            .IsDescending(true)
            .HasDatabaseName("IX_Recordings_CreatedUtc");
        builder.HasIndex(recording => recording.ChecksumSha256)
            .HasDatabaseName("IX_Recordings_ChecksumSha256");
        builder.HasIndex(recording => new { recording.ThumbnailState, recording.UploadedUtc })
            .HasDatabaseName("IX_Recordings_ThumbnailState_UploadedUtc");
        builder.HasIndex(recording => recording.UploadedUtc)
            .IsDescending(true)
            .HasDatabaseName("IX_Recordings_UploadedUtc");
        builder.HasIndex(recording => new { recording.DurationMilliseconds, recording.CreatedUtc })
            .HasDatabaseName("IX_Recordings_DurationMilliseconds_CreatedUtc");
        builder.HasIndex(recording => new { recording.SizeBytes, recording.CreatedUtc })
            .HasDatabaseName("IX_Recordings_SizeBytes_CreatedUtc");
        builder.HasIndex(recording => new { recording.IsMotion, recording.CreatedUtc })
            .HasDatabaseName("IX_Recordings_IsMotion_CreatedUtc");
    }
}
