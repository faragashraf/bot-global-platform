using BotGlobal.Catalog.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Microsoft.EntityFrameworkCore.Storage.ValueConversion;

namespace BotGlobal.Catalog.Infrastructure.Configurations;

internal sealed class ProductLinkConfiguration : IEntityTypeConfiguration<ProductLink>
{
    public void Configure(EntityTypeBuilder<ProductLink> builder)
    {
        builder.ToTable("ProductLinks", CatalogDbContext.Schema, table =>
        {
            table.HasCheckConstraint("CK_ProductLinks_Type", "[Type] IN ('support', 'privacy', 'store', 'download', 'website')");
            table.HasCheckConstraint("CK_ProductLinks_SortOrder", "[SortOrder] >= 0");
        });

        builder.HasKey(link => link.Id);
        builder.Property(link => link.Type)
            .HasConversion(new ValueConverter<ProductLinkType, string>(
                type => type == ProductLinkType.Support
                    ? "support"
                    : type == ProductLinkType.Privacy
                        ? "privacy"
                        : type == ProductLinkType.Store
                            ? "store"
                            : type == ProductLinkType.Download
                                ? "download"
                                : "website",
                value => value == "support"
                    ? ProductLinkType.Support
                    : value == "privacy"
                        ? ProductLinkType.Privacy
                        : value == "store"
                            ? ProductLinkType.Store
                            : value == "download"
                                ? ProductLinkType.Download
                                : ProductLinkType.Website))
            .HasColumnType("varchar(16)")
            .IsRequired();
        builder.Property(link => link.Url).HasMaxLength(2048).IsRequired();
        builder.Property(link => link.LabelEn).HasMaxLength(200);
        builder.Property(link => link.LabelAr).HasMaxLength(200);
        builder.Property(link => link.SortOrder).IsRequired();

        builder.Property<byte[]>("UrlHash")
            .HasColumnType("binary(32)")
            .HasComputedColumnSql(
                "CONVERT(binary(32), HASHBYTES('SHA2_256', CONVERT(varbinary(max), [Url])))",
                stored: true);

        builder.HasIndex("ProductId", "Type", "UrlHash")
            .IsUnique()
            .HasDatabaseName("UX_ProductLinks_ProductId_Type_Url");
    }
}
