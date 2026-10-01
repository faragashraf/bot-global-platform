using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using SentriCam.Application.Messaging;

namespace SentriCam.Infrastructure.Persistence.Configurations;

internal sealed class OutboxMessageConfiguration : IEntityTypeConfiguration<OutboxMessage>
{
    public void Configure(EntityTypeBuilder<OutboxMessage> builder)
    {
        builder.ToTable("OutboxMessages");
        builder.HasKey(message => message.Id);
        builder.Property(message => message.Id).ValueGeneratedNever();
        builder.Property(message => message.EventId).IsRequired();
        builder.Property(message => message.EventType).HasMaxLength(200).IsRequired();
        builder.Property(message => message.SchemaVersion).IsRequired();
        builder.Property(message => message.PayloadJson).HasColumnType("nvarchar(max)").IsRequired();
        builder.Property(message => message.OccurredAtUtc).HasPrecision(7).IsRequired();
        builder.Property(message => message.CreatedAtUtc).HasPrecision(7).IsRequired();
        builder.Property(message => message.ProcessedAtUtc).HasPrecision(7);
        builder.Property(message => message.AttemptCount).IsRequired();
        builder.Property<byte[]>("RowVersion").IsRowVersionToken();
        builder.HasIndex(message => message.EventId)
            .IsUnique()
            .HasDatabaseName("UX_OutboxMessages_EventId");
        builder.HasIndex(message => new { message.ProcessedAtUtc, message.CreatedAtUtc })
            .HasDatabaseName("IX_OutboxMessages_ProcessedAtUtc_CreatedAtUtc");
    }
}
