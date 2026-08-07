using System.Text.Json;
using BotGlobal.Catalog.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.ChangeTracking;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Microsoft.EntityFrameworkCore.Storage.ValueConversion;

namespace BotGlobal.Catalog.Infrastructure.Configurations;

internal sealed class ProductLocalizationConfiguration : IEntityTypeConfiguration<ProductLocalization>
{
    public void Configure(EntityTypeBuilder<ProductLocalization> builder)
    {
        builder.ToTable("ProductLocalizations", CatalogDbContext.Schema, table =>
        {
            table.HasCheckConstraint("CK_ProductLocalizations_Language", "[Language] IN ('en', 'ar')");
            table.HasCheckConstraint(
                "CK_ProductLocalizations_PlatformsJson",
                "ISJSON([PlatformsJson]) = 1 AND LEFT(LTRIM([PlatformsJson]), 1) = '['");
            table.HasCheckConstraint(
                "CK_ProductLocalizations_TechnologiesJson",
                "ISJSON([TechnologiesJson]) = 1 AND LEFT(LTRIM([TechnologiesJson]), 1) = '['");
        });

        builder.HasKey(localization => new { localization.ProductId, localization.Language });

        builder.Property(localization => localization.Language)
            .HasColumnType("char(2)")
            .IsRequired();
        builder.Property(localization => localization.Name)
            .HasMaxLength(200)
            .IsRequired();
        builder.Property(localization => localization.ShortDescription)
            .HasMaxLength(600)
            .IsRequired();
        builder.Property(localization => localization.Description)
            .HasColumnType("nvarchar(max)")
            .IsRequired();
        builder.Property(localization => localization.DisplayStatus)
            .HasMaxLength(150);

        ConfigureJsonCollection(builder.Property(localization => localization.Platforms), "PlatformsJson");
        ConfigureJsonCollection(builder.Property(localization => localization.Technologies), "TechnologiesJson");

        builder.HasData(
            new
            {
                ProductId = CatalogSeed.SentriCamProductId,
                Language = "en",
                Name = "SentriCam",
                ShortDescription = "An existing BOT GLOBAL product with public catalog details in preparation.",
                Description = "SentriCam is identified in the BOT GLOBAL platform documentation as an existing product. Verified public feature, platform, media, availability, and support details have not yet been published, so this entry intentionally makes no additional product claims.",
                DisplayStatus = "Details pending",
                Platforms = Array.Empty<string>(),
                Technologies = Array.Empty<string>()
            },
            new
            {
                ProductId = CatalogSeed.SentriCamProductId,
                Language = "ar",
                Name = "SentriCam",
                ShortDescription = "منتج قائم من BOT GLOBAL، ويجري حاليًا إعداد تفاصيله للنشر في الكتالوج العام.",
                Description = "تُعرّف وثائق منصة BOT GLOBAL منتج SentriCam باعتباره منتجًا قائمًا. لم تُنشر بعد تفاصيل موثقة للعامة حول الميزات أو المنصات أو الوسائط أو الإتاحة أو الدعم؛ لذلك لا يتضمن هذا السجل أي ادعاءات إضافية عن المنتج.",
                DisplayStatus = "التفاصيل قيد الإعداد",
                Platforms = Array.Empty<string>(),
                Technologies = Array.Empty<string>()
            });
    }

    private static void ConfigureJsonCollection(
        PropertyBuilder<IReadOnlyList<string>> property,
        string columnName)
    {
        var converter = new ValueConverter<IReadOnlyList<string>, string>(
            values => JsonSerializer.Serialize(values, (JsonSerializerOptions?)null),
            json => JsonSerializer.Deserialize<string[]>(json, (JsonSerializerOptions?)null) ?? Array.Empty<string>());
        var comparer = new ValueComparer<IReadOnlyList<string>>(
            (left, right) => left != null && right != null && left.SequenceEqual(right),
            values => values.Aggregate(0, (hash, value) => HashCode.Combine(hash, value.GetHashCode())),
            values => values.ToArray());

        property
            .HasColumnName(columnName)
            .HasConversion(converter)
            .HasColumnType("nvarchar(max)")
            .HasDefaultValueSql("N'[]'")
            .IsRequired();

        property.Metadata.SetValueComparer(comparer);
    }
}
