using BotGlobal.PlatformClients.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace BotGlobal.PlatformClients.Infrastructure.Persistence.Configurations;

public sealed class PlatformClientConfiguration
    : IEntityTypeConfiguration<PlatformClient>
{
    public void Configure(EntityTypeBuilder<PlatformClient> builder)
    {
        builder.ToTable(
            "Clients",
            "platform_clients",
            table => table.HasCheckConstraint(
                "CK_PlatformClients_Status",
                "[Status] IN ('Active','Disabled')"));

        builder.HasKey(client => client.Id);

        builder.Property(client => client.ClientKey)
            .HasMaxLength(PlatformClient.ClientKeyMaxLength)
            .IsUnicode(false)
            .IsRequired();

        builder.Property(client => client.DisplayName)
            .HasMaxLength(PlatformClient.DisplayNameMaxLength)
            .IsRequired();

        builder.Property(client => client.Status)
            .HasConversion<string>()
            .HasMaxLength(16)
            .IsUnicode(false)
            .IsRequired();

        builder.Property(client => client.CreatedAtUtc)
            .HasColumnType("datetimeoffset")
            .IsRequired();

        builder.Property(client => client.DisabledAtUtc)
            .HasColumnType("datetimeoffset");

        builder.HasIndex(client => client.ClientKey)
            .IsUnique()
            .HasDatabaseName("UX_PlatformClients_ClientKey");

        builder.HasMany(client => client.Credentials)
            .WithOne()
            .HasForeignKey(credential => credential.ClientId)
            .OnDelete(DeleteBehavior.Cascade);

        builder.HasMany(client => client.Capabilities)
            .WithOne()
            .HasForeignKey(capability => capability.ClientId)
            .OnDelete(DeleteBehavior.Cascade);

        builder.Navigation(client => client.Credentials)
            .UsePropertyAccessMode(PropertyAccessMode.Field);

        builder.Navigation(client => client.Capabilities)
            .UsePropertyAccessMode(PropertyAccessMode.Field);
    }
}
