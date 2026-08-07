using BotGlobal.Catalog.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace BotGlobal.Catalog.Infrastructure.Configurations;

internal sealed class ProductReleaseConfiguration : IEntityTypeConfiguration<ProductRelease>
{
    public void Configure(EntityTypeBuilder<ProductRelease> builder)
    {
        builder.ToTable("ProductReleases", CatalogDbContext.Schema, table =>
        {
            table.HasCheckConstraint("CK_ProductReleases_PublicationStatus", "[PublicationStatus] IN ('Draft', 'Published', 'Archived')");
            table.HasCheckConstraint("CK_ProductReleases_SortOrder", "[SortOrder] >= 0");
        });

        builder.HasKey(release => release.Id);
        builder.Property(release => release.Version).HasMaxLength(64).IsRequired();
        builder.Property(release => release.PublicationStatus)
            .HasConversion<string>()
            .HasColumnType("varchar(16)")
            .IsRequired();
        builder.Property(release => release.ReleasedAtUtc).HasColumnType("datetimeoffset");
        builder.Property(release => release.NotesEn).HasColumnType("nvarchar(max)");
        builder.Property(release => release.NotesAr).HasColumnType("nvarchar(max)");
        builder.Property(release => release.SortOrder).IsRequired();

        builder.HasIndex(release => new { release.ProductId, release.Version })
            .IsUnique()
            .HasDatabaseName("UX_ProductReleases_ProductId_Version");
    }
}
