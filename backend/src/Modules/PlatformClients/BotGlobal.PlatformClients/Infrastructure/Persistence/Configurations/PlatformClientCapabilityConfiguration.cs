using BotGlobal.PlatformClients.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace BotGlobal.PlatformClients.Infrastructure.Persistence.Configurations;

public sealed class PlatformClientCapabilityConfiguration
    : IEntityTypeConfiguration<PlatformClientCapability>
{
    public void Configure(EntityTypeBuilder<PlatformClientCapability> builder)
    {
        builder.ToTable("Capabilities", "platform_clients");

        builder.HasKey(
            capability => new
            {
                capability.ClientId,
                capability.Capability
            });

        builder.Property(capability => capability.Capability)
            .HasMaxLength(PlatformClientCapability.CapabilityMaxLength)
            .IsUnicode(false)
            .IsRequired();

        builder.Property(capability => capability.GrantedAtUtc)
            .HasColumnType("datetimeoffset")
            .IsRequired();
    }
}
