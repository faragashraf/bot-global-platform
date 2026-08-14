using BotGlobal.PlatformClients.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace BotGlobal.PlatformClients.Infrastructure.Persistence.Configurations;

public sealed class PlatformClientCredentialConfiguration
    : IEntityTypeConfiguration<PlatformClientCredential>
{
    public void Configure(EntityTypeBuilder<PlatformClientCredential> builder)
    {
        builder.ToTable(
            "Credentials",
            "platform_clients",
            table =>
            {
                table.HasCheckConstraint(
                    "CK_PlatformClientCredentials_Expiry",
                    "[ExpiresAtUtc] IS NULL OR [ExpiresAtUtc] > [CreatedAtUtc]");
                table.HasCheckConstraint(
                    "CK_PlatformClientCredentials_RevokeTime",
                    "[RevokedAtUtc] IS NULL OR [RevokedAtUtc] >= [CreatedAtUtc]");
            });

        builder.HasKey(credential => credential.Id);

        builder.Property(credential => credential.SecretHash)
            .HasColumnType("binary(32)")
            .IsRequired();

        builder.Property(credential => credential.CreatedAtUtc)
            .HasColumnType("datetimeoffset")
            .IsRequired();

        builder.Property(credential => credential.ExpiresAtUtc)
            .HasColumnType("datetimeoffset");

        builder.Property(credential => credential.RevokedAtUtc)
            .HasColumnType("datetimeoffset");

        builder.Ignore(credential => credential.IsRevoked);

        builder.HasIndex(
                credential => new
                {
                    credential.ClientId,
                    credential.RevokedAtUtc,
                    credential.ExpiresAtUtc
                })
            .HasDatabaseName(
                "IX_PlatformClientCredentials_Client_Usability");
    }
}
