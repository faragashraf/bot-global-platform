using BotGlobal.Catalog.Domain;
using BotGlobal.Catalog.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Metadata;

namespace BotGlobal.UnitTests.Catalog;

public sealed class CatalogDbContextModelTests
{
    private readonly IModel _model;

    public CatalogDbContextModelTests()
    {
        var options = new DbContextOptionsBuilder<CatalogDbContext>()
            .UseSqlServer("Server=localhost;Database=CatalogModelTests;Trusted_Connection=True;TrustServerCertificate=True")
            .Options;
        using var context = new CatalogDbContext(options);
        _model = context.GetService<IDesignTimeModel>().Model;
    }

    [Theory]
    [InlineData(typeof(Product), "Products")]
    [InlineData(typeof(ProductLocalization), "ProductLocalizations")]
    [InlineData(typeof(ProductMedia), "ProductMedia")]
    [InlineData(typeof(ProductLink), "ProductLinks")]
    [InlineData(typeof(ProductRelease), "ProductReleases")]
    public void Every_catalog_entity_uses_the_catalog_schema(Type entityType, string tableName)
    {
        var entity = AssertEntity(entityType);

        Assert.Equal(CatalogDbContext.Schema, entity.GetSchema());
        Assert.Equal(tableName, entity.GetTableName());
    }

    [Fact]
    public void Product_has_unique_category_and_slug_index()
    {
        var entity = AssertEntity(typeof(Product));
        var index = Assert.Single(entity.GetIndexes(), candidate =>
            candidate.GetDatabaseName() == "UX_Products_Category_Slug");

        Assert.True(index.IsUnique);
        Assert.Equal([nameof(Product.Category), nameof(Product.Slug)], index.Properties.Select(property => property.Name));
    }

    [Fact]
    public void Child_entities_cascade_on_product_delete()
    {
        var childTypes = new[]
        {
            typeof(ProductLocalization),
            typeof(ProductMedia),
            typeof(ProductLink),
            typeof(ProductRelease)
        };

        foreach (var childType in childTypes)
        {
            var foreignKey = Assert.Single(AssertEntity(childType).GetForeignKeys());
            Assert.Equal(DeleteBehavior.Cascade, foreignKey.DeleteBehavior);
        }
    }

    [Fact]
    public void Localization_collections_are_stored_as_json_columns()
    {
        var entity = AssertEntity(typeof(ProductLocalization));

        Assert.Equal("PlatformsJson", entity.FindProperty(nameof(ProductLocalization.Platforms))?.GetColumnName());
        Assert.Equal("TechnologiesJson", entity.FindProperty(nameof(ProductLocalization.Technologies))?.GetColumnName());
        Assert.Equal("nvarchar(max)", entity.FindProperty(nameof(ProductLocalization.Platforms))?.GetColumnType());
        Assert.Equal("nvarchar(max)", entity.FindProperty(nameof(ProductLocalization.Technologies))?.GetColumnType());
    }

    [Fact]
    public void Product_link_uniqueness_uses_the_full_url_hash()
    {
        var entity = AssertEntity(typeof(ProductLink));
        var index = Assert.Single(entity.GetIndexes(), candidate =>
            candidate.GetDatabaseName() == "UX_ProductLinks_ProductId_Type_Url");

        Assert.True(index.IsUnique);
        Assert.Equal(["ProductId", "Type", "UrlHash"], index.Properties.Select(property => property.Name));
        Assert.NotNull(entity.FindProperty("UrlHash")?.GetComputedColumnSql());
    }

    [Fact]
    public void SentriCam_seed_is_deterministic_and_matches_the_public_catalog_content()
    {
        var expectedId = Guid.Parse("a5b5930e-8499-4b52-9a76-6cc0de0f4a11");
        var product = Assert.Single(AssertEntity(typeof(Product)).GetSeedData());

        Assert.Equal(expectedId, product[nameof(Product.Id)]);
        Assert.Equal("sentricam", product[nameof(Product.Slug)]);
        Assert.Equal(ProductCategory.App, product[nameof(Product.Category)]);
        Assert.Equal(PublicationStatus.Published, product[nameof(Product.PublicationStatus)]);
        Assert.Equal(true, product[nameof(Product.IsFeatured)]);
        Assert.Equal(0, product[nameof(Product.SortOrder)]);
        Assert.Null(product[nameof(Product.PublishedAtUtc)]);

        var localizations = AssertEntity(typeof(ProductLocalization))
            .GetSeedData()
            .ToDictionary(row => Assert.IsType<string>(row[nameof(ProductLocalization.Language)]));
        Assert.Equal(2, localizations.Count);

        AssertSeedLocalization(
            localizations["en"],
            expectedId,
            "SentriCam",
            "Local-first camera monitoring for homes and small teams, pairing Android camera devices with a private Hub and browser dashboard.",
            "SentriCam turns Android phones into managed monitoring devices connected to a local Hub on a home or office computer. The setup journey covers Hub installation, storage policy selection, QR pairing, device health, recording control, local recording archive, and LAN live view. The V1 boundary is deliberately local-first: SignalR coordinates authorized sessions, while video stays on the local network and cloud/AI capabilities remain deferred.",
            "V1 runtime showcase",
            ["Android camera device", "SentriCam Hub", "Browser dashboard"],
            ["Kotlin", "CameraX", "ASP.NET Core", "SignalR", "WebRTC LAN live view"]);
        AssertSeedLocalization(
            localizations["ar"],
            expectedId,
            "SentriCam",
            "مراقبة كاميرات محلية للمنازل والفرق الصغيرة، تربط أجهزة أندرويد بلوحة Hub خاصة ولوحة تحكم من المتصفح.",
            "يحول SentriCam هواتف أندرويد إلى أجهزة مراقبة مُدارة تتصل بـ Hub محلي على كمبيوتر في المنزل أو المكتب. رحلة الإعداد تشمل تثبيت الـ Hub، اختيار سياسة التخزين، الاقتران عبر QR، متابعة صحة الجهاز، التحكم في التسجيل، أرشيف التسجيلات المحلي، والبث المباشر داخل الشبكة المحلية. حدود V1 مقصودة: SignalR ينسق الجلسات المصرح بها، بينما يظل الفيديو داخل الشبكة المحلية وتبقى إمكانات السحابة والذكاء الاصطناعي مؤجلة.",
            "استعراض تشغيلي V1",
            ["جهاز كاميرا أندرويد", "SentriCam Hub", "لوحة تحكم المتصفح"],
            ["Kotlin", "CameraX", "ASP.NET Core", "SignalR", "بث مباشر WebRTC داخل الشبكة المحلية"]);

        Assert.Empty(AssertEntity(typeof(ProductMedia)).GetSeedData());
        Assert.Empty(AssertEntity(typeof(ProductLink)).GetSeedData());
        Assert.Empty(AssertEntity(typeof(ProductRelease)).GetSeedData());
    }

    private static void AssertSeedLocalization(
        IDictionary<string, object?> localization,
        Guid productId,
        string name,
        string shortDescription,
        string description,
        string displayStatus,
        string[] platforms,
        string[] technologies)
    {
        Assert.Equal(productId, localization[nameof(ProductLocalization.ProductId)]);
        Assert.Equal(name, localization[nameof(ProductLocalization.Name)]);
        Assert.Equal(shortDescription, localization[nameof(ProductLocalization.ShortDescription)]);
        Assert.Equal(description, localization[nameof(ProductLocalization.Description)]);
        Assert.Equal(displayStatus, localization[nameof(ProductLocalization.DisplayStatus)]);
        Assert.Equal(platforms, Assert.IsAssignableFrom<IEnumerable<string>>(localization[nameof(ProductLocalization.Platforms)]));
        Assert.Equal(technologies, Assert.IsAssignableFrom<IEnumerable<string>>(localization[nameof(ProductLocalization.Technologies)]));
    }

    private IEntityType AssertEntity(Type type) =>
        _model.FindEntityType(type) ?? throw new Xunit.Sdk.XunitException($"{type.Name} is missing from the Catalog model.");
}
