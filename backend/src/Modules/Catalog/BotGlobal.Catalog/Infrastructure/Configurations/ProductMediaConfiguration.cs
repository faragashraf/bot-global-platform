using BotGlobal.Catalog.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Microsoft.EntityFrameworkCore.Storage.ValueConversion;

namespace BotGlobal.Catalog.Infrastructure.Configurations;

internal sealed class ProductMediaConfiguration : IEntityTypeConfiguration<ProductMedia>
{
    public void Configure(EntityTypeBuilder<ProductMedia> builder)
    {
        builder.ToTable("ProductMedia", CatalogDbContext.Schema, table =>
        {
            table.HasCheckConstraint("CK_ProductMedia_Kind", "[Kind] IN ('hero', 'screenshot')");
            table.HasCheckConstraint("CK_ProductMedia_SortOrder", "[SortOrder] >= 0");
            table.HasCheckConstraint("CK_ProductMedia_ByteLength", "[ByteLength] IS NULL OR [ByteLength] >= 0");
            table.HasCheckConstraint("CK_ProductMedia_Width", "[Width] IS NULL OR [Width] > 0");
            table.HasCheckConstraint("CK_ProductMedia_Height", "[Height] IS NULL OR [Height] > 0");
        });

        builder.HasKey(media => media.Id);
        builder.Property(media => media.Kind)
            .HasConversion(new ValueConverter<ProductMediaKind, string>(
                kind => kind == ProductMediaKind.Hero ? "hero" : "screenshot",
                value => value == "hero" ? ProductMediaKind.Hero : ProductMediaKind.Screenshot))
            .HasColumnType("varchar(16)")
            .IsRequired();
        builder.Property(media => media.StorageProvider).HasMaxLength(40).IsRequired();
        builder.Property(media => media.StorageKey).HasMaxLength(500).IsRequired();
        builder.Property(media => media.ContentType).HasMaxLength(100).IsRequired();
        builder.Property(media => media.AltTextEn).HasMaxLength(300);
        builder.Property(media => media.AltTextAr).HasMaxLength(300);
        builder.Property(media => media.SortOrder).IsRequired();

        builder.HasIndex(media => new { media.StorageProvider, media.StorageKey })
            .IsUnique()
            .HasDatabaseName("UX_ProductMedia_StorageProvider_StorageKey");
        builder.HasIndex(media => media.ProductId)
            .IsUnique()
            .HasFilter("[Kind] = 'hero'")
            .HasDatabaseName("UX_ProductMedia_ProductId_Hero");
    }
}
