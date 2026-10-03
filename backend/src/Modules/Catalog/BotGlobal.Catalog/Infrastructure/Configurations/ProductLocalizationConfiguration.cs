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
                ShortDescription = "Local-first camera monitoring for homes and small teams, pairing Android camera devices with a private Hub and browser dashboard.",
                Description = "SentriCam turns Android phones into managed monitoring devices connected to a local Hub on a home or office computer. The setup journey covers Hub installation, storage policy selection, QR pairing, device health, recording control, local recording archive, and LAN live view. The V1 boundary is deliberately local-first: SignalR coordinates authorized sessions, while video stays on the local network and cloud/AI capabilities remain deferred.",
                DisplayStatus = "V1 runtime showcase",
                Platforms = new[] { "Android camera device", "SentriCam Hub", "Browser dashboard" },
                Technologies = new[] { "Kotlin", "CameraX", "ASP.NET Core", "SignalR", "WebRTC LAN live view" }
            },
            new
            {
                ProductId = CatalogSeed.SentriCamProductId,
                Language = "ar",
                Name = "SentriCam",
                ShortDescription = "مراقبة كاميرات محلية للمنازل والفرق الصغيرة، تربط أجهزة أندرويد بلوحة Hub خاصة ولوحة تحكم من المتصفح.",
                Description = "يحول SentriCam هواتف أندرويد إلى أجهزة مراقبة مُدارة تتصل بـ Hub محلي على كمبيوتر في المنزل أو المكتب. رحلة الإعداد تشمل تثبيت الـ Hub، اختيار سياسة التخزين، الاقتران عبر QR، متابعة صحة الجهاز، التحكم في التسجيل، أرشيف التسجيلات المحلي، والبث المباشر داخل الشبكة المحلية. حدود V1 مقصودة: SignalR ينسق الجلسات المصرح بها، بينما يظل الفيديو داخل الشبكة المحلية وتبقى إمكانات السحابة والذكاء الاصطناعي مؤجلة.",
                DisplayStatus = "استعراض تشغيلي V1",
                Platforms = new[] { "جهاز كاميرا أندرويد", "SentriCam Hub", "لوحة تحكم المتصفح" },
                Technologies = new[] { "Kotlin", "CameraX", "ASP.NET Core", "SignalR", "بث مباشر WebRTC داخل الشبكة المحلية" }
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
