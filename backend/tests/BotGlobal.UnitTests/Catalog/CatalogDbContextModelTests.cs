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
            "An existing BOT GLOBAL product with public catalog details in preparation.",
            "SentriCam is identified in the BOT GLOBAL platform documentation as an existing product. Verified public feature, platform, media, availability, and support details have not yet been published, so this entry intentionally makes no additional product claims.",
            "Details pending");
        AssertSeedLocalization(
            localizations["ar"],
            expectedId,
            "SentriCam",
            "منتج قائم من BOT GLOBAL، ويجري حاليًا إعداد تفاصيله للنشر في الكتالوج العام.",
            "تُعرّف وثائق منصة BOT GLOBAL منتج SentriCam باعتباره منتجًا قائمًا. لم تُنشر بعد تفاصيل موثقة للعامة حول الميزات أو المنصات أو الوسائط أو الإتاحة أو الدعم؛ لذلك لا يتضمن هذا السجل أي ادعاءات إضافية عن المنتج.",
            "التفاصيل قيد الإعداد");

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
        string displayStatus)
    {
        Assert.Equal(productId, localization[nameof(ProductLocalization.ProductId)]);
        Assert.Equal(name, localization[nameof(ProductLocalization.Name)]);
        Assert.Equal(shortDescription, localization[nameof(ProductLocalization.ShortDescription)]);
        Assert.Equal(description, localization[nameof(ProductLocalization.Description)]);
        Assert.Equal(displayStatus, localization[nameof(ProductLocalization.DisplayStatus)]);
        Assert.Empty(Assert.IsAssignableFrom<IEnumerable<string>>(localization[nameof(ProductLocalization.Platforms)]));
        Assert.Empty(Assert.IsAssignableFrom<IEnumerable<string>>(localization[nameof(ProductLocalization.Technologies)]));
    }

    private IEntityType AssertEntity(Type type) =>
        _model.FindEntityType(type) ?? throw new Xunit.Sdk.XunitException($"{type.Name} is missing from the Catalog model.");
}
