using BotGlobal.Catalog.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Microsoft.EntityFrameworkCore.Storage.ValueConversion;

namespace BotGlobal.Catalog.Infrastructure.Configurations;

internal sealed class ProductConfiguration : IEntityTypeConfiguration<Product>
{
    public void Configure(EntityTypeBuilder<Product> builder)
    {
        builder.ToTable("Products", CatalogDbContext.Schema, table =>
        {
            table.HasCheckConstraint("CK_Products_Category", "[Category] IN ('app', 'game', 'program')");
            table.HasCheckConstraint("CK_Products_PublicationStatus", "[PublicationStatus] IN ('Draft', 'Published', 'Archived')");
            table.HasCheckConstraint("CK_Products_FeaturedPublished", "[IsFeatured] = 0 OR [PublicationStatus] = 'Published'");
            table.HasCheckConstraint("CK_Products_SortOrder", "[SortOrder] >= 0");
            table.HasCheckConstraint(
                "CK_Products_Slug",
                "LEN([Slug]) > 0 AND [Slug] COLLATE Latin1_General_100_BIN2 = LOWER([Slug]) AND [Slug] NOT LIKE '%[^a-z0-9-]%' AND [Slug] NOT LIKE '-%' AND [Slug] NOT LIKE '%-' AND [Slug] NOT LIKE '%--%'");
        });

        builder.HasKey(product => product.Id);

        builder.Property(product => product.Slug)
            .HasMaxLength(100)
            .IsRequired();

        builder.Property(product => product.Category)
            .HasConversion(new ValueConverter<ProductCategory, string>(
                category => category == ProductCategory.App
                    ? "app"
                    : category == ProductCategory.Game
                        ? "game"
                        : "program",
                value => value == "app"
                    ? ProductCategory.App
                    : value == "game"
                        ? ProductCategory.Game
                        : ProductCategory.Program))
            .HasColumnType("varchar(16)")
            .IsRequired();

        builder.Property(product => product.PublicationStatus)
            .HasConversion<string>()
            .HasColumnType("varchar(16)")
            .IsRequired();

        builder.Property(product => product.IsFeatured).IsRequired();
        builder.Property(product => product.SortOrder).IsRequired();
        builder.Property(product => product.PublishedAtUtc).HasColumnType("datetimeoffset");
        builder.Ignore(product => product.CanBePhysicallyDeleted);

        builder.HasIndex(product => new { product.Category, product.Slug })
            .IsUnique()
            .HasDatabaseName("UX_Products_Category_Slug");

        builder.HasMany(product => product.Localizations)
            .WithOne()
            .HasForeignKey(localization => localization.ProductId)
            .OnDelete(DeleteBehavior.Cascade);
        builder.Navigation(product => product.Localizations).UsePropertyAccessMode(PropertyAccessMode.Field);

        builder.HasMany(product => product.Media)
            .WithOne()
            .HasForeignKey(media => media.ProductId)
            .OnDelete(DeleteBehavior.Cascade);
        builder.Navigation(product => product.Media).UsePropertyAccessMode(PropertyAccessMode.Field);

        builder.HasMany(product => product.Links)
            .WithOne()
            .HasForeignKey(link => link.ProductId)
            .OnDelete(DeleteBehavior.Cascade);
        builder.Navigation(product => product.Links).UsePropertyAccessMode(PropertyAccessMode.Field);

        builder.HasMany(product => product.Releases)
            .WithOne()
            .HasForeignKey(release => release.ProductId)
            .OnDelete(DeleteBehavior.Cascade);
        builder.Navigation(product => product.Releases).UsePropertyAccessMode(PropertyAccessMode.Field);

        builder.HasData(new
        {
            Id = CatalogSeed.SentriCamProductId,
            Slug = "sentricam",
            Category = ProductCategory.App,
            PublicationStatus = PublicationStatus.Published,
            IsFeatured = true,
            SortOrder = 0,
            PublishedAtUtc = (DateTimeOffset?)null
        });
    }
}
