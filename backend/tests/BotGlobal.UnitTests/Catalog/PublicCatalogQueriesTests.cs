using System.Text.Json;
using BotGlobal.Catalog.Application;
using BotGlobal.Catalog.Contracts;
using BotGlobal.Catalog.Domain;
using BotGlobal.Catalog.Infrastructure;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.UnitTests.Catalog;

public sealed class PublicCatalogQueriesTests
{
    [Fact]
    public async Task Public_list_returns_only_published_products_in_deterministic_order()
    {
        await using var context = CreateContext();
        var later = CreateProduct("zulu", ProductCategory.App, sortOrder: 10, publish: true);
        var alphabeticallySecond = CreateProduct("bravo", ProductCategory.Game, sortOrder: 5, publish: true);
        var alphabeticallyFirst = CreateProduct("alpha", ProductCategory.Program, sortOrder: 5, publish: true);
        var draft = CreateProduct("draft", ProductCategory.App, sortOrder: 0);
        var archived = CreateProduct("archived", ProductCategory.App, sortOrder: 0, publish: true);
        archived.Archive();
        context.AddRange(later, alphabeticallySecond, alphabeticallyFirst, draft, archived);
        await context.SaveChangesAsync();

        var products = await CreateQueries(context).GetProductsAsync(null, null);

        Assert.Equal(["alpha", "bravo", "zulu"], products.Select(product => product.Slug));
        Assert.DoesNotContain(products, product => product.Slug is "draft" or "archived");
    }

    [Theory]
    [InlineData(ProductCategory.App, null, "app-featured", "app-standard")]
    [InlineData(null, true, "app-featured", "game-featured")]
    [InlineData(null, false, "app-standard")]
    [InlineData(ProductCategory.App, true, "app-featured")]
    public async Task Public_list_applies_optional_category_and_featured_filters(
        ProductCategory? category,
        bool? featured,
        params string[] expectedSlugs)
    {
        await using var context = CreateContext();
        context.AddRange(
            CreateProduct("app-featured", ProductCategory.App, publish: true, featured: true),
            CreateProduct("app-standard", ProductCategory.App, publish: true),
            CreateProduct("game-featured", ProductCategory.Game, publish: true, featured: true));
        await context.SaveChangesAsync();

        var products = await CreateQueries(context).GetProductsAsync(category, featured);

        Assert.Equal(expectedSlugs, products.Select(product => product.Slug));
    }

    [Fact]
    public async Task Public_lookup_matches_category_and_slug_and_hides_non_published_products()
    {
        await using var context = CreateContext();
        var published = CreateProduct("published", ProductCategory.App, publish: true);
        var draft = CreateProduct("draft", ProductCategory.App);
        var archived = CreateProduct("archived", ProductCategory.App, publish: true);
        archived.Archive();
        context.AddRange(published, draft, archived);
        await context.SaveChangesAsync();
        var queries = CreateQueries(context);

        var result = await queries.GetProductAsync(ProductCategory.App, "published");

        Assert.NotNull(result);
        Assert.Equal(published.Id, result.Id);
        Assert.Null(await queries.GetProductAsync(ProductCategory.Game, "published"));
        Assert.Null(await queries.GetProductAsync(ProductCategory.App, "missing"));
        Assert.Null(await queries.GetProductAsync(ProductCategory.App, "draft"));
        Assert.Null(await queries.GetProductAsync(ProductCategory.App, "archived"));
    }

    [Fact]
    public async Task Public_dto_maps_bilingual_content_platforms_and_technologies()
    {
        await using var context = CreateContext();
        var product = Product.Create(Guid.NewGuid(), "localized", ProductCategory.Program);
        product.SetLocalization(
            "en",
            "English name",
            "English short",
            "English description",
            "Available",
            ["Windows", "Web"],
            [".NET", "Angular"]);
        product.SetLocalization(
            "ar",
            "الاسم العربي",
            "الوصف المختصر",
            "الوصف العربي",
            "متاح",
            ["ويندوز", "الويب"],
            ["دوت نت", "أنجولار"]);
        product.Publish(DateTimeOffset.UtcNow);
        context.Add(product);
        await context.SaveChangesAsync();

        var result = await CreateQueries(context).GetProductAsync(ProductCategory.Program, "localized");

        Assert.NotNull(result);
        Assert.Equal(new LocalizedTextDto("English name", "الاسم العربي"), result.Name);
        Assert.Equal(new LocalizedTextDto("Available", "متاح"), result.Status);
        Assert.Equal(
            [new LocalizedTextDto("Windows", "ويندوز"), new LocalizedTextDto("Web", "الويب")],
            result.Platforms);
        Assert.Equal(
            [new LocalizedTextDto(".NET", "دوت نت"), new LocalizedTextDto("Angular", "أنجولار")],
            result.Technologies);
    }

    [Fact]
    public async Task Products_without_resolvable_media_or_links_return_clean_empty_values()
    {
        await using var context = CreateContext();
        var product = CreateProduct("clean", ProductCategory.App, publish: true);
        product.AddMedia(new ProductMedia(
            Guid.NewGuid(),
            product.Id,
            ProductMediaKind.Hero,
            "unconfigured",
            "private/hero.png",
            "image/png",
            0));
        context.Add(product);
        await context.SaveChangesAsync();

        var result = await CreateQueries(context).GetProductAsync(ProductCategory.App, "clean");

        Assert.NotNull(result);
        Assert.Null(result.HeroMedia);
        Assert.Empty(result.Screenshots);
        Assert.Empty(result.Links);
    }

    [Fact]
    public async Task Resolved_media_and_valid_persisted_links_map_without_storage_details()
    {
        await using var context = CreateContext();
        var product = CreateProduct("media", ProductCategory.App, publish: true);
        product.AddMedia(new ProductMedia(
            Guid.NewGuid(),
            product.Id,
            ProductMediaKind.Hero,
            "assets",
            "catalog/hero.png",
            "image/png",
            0,
            altTextEn: "Hero",
            altTextAr: "الصورة الرئيسية"));
        product.AddMedia(new ProductMedia(
            Guid.NewGuid(),
            product.Id,
            ProductMediaKind.Screenshot,
            "assets",
            "catalog/screen.png",
            "image/png",
            1));
        product.ReplaceLinks([
            new ProductLink(
                Guid.NewGuid(),
                product.Id,
                ProductLinkType.Support,
                "https://example.com/support",
                0,
                "Support",
                "الدعم")
        ]);
        context.Add(product);
        await context.SaveChangesAsync();
        var resolver = new PrefixMediaUrlResolver("https://cdn.example.com/");

        var result = await CreateQueries(context, resolver).GetProductAsync(ProductCategory.App, "media");

        Assert.NotNull(result);
        Assert.Equal("https://cdn.example.com/catalog/hero.png", result.HeroMedia?.Url);
        Assert.Equal(new LocalizedTextDto("Hero", "الصورة الرئيسية"), result.HeroMedia?.Alt);
        Assert.Equal("https://cdn.example.com/catalog/screen.png", Assert.Single(result.Screenshots).Url);
        Assert.Equal("support", Assert.Single(result.Links).Type);
        Assert.Equal("https://example.com/support", Assert.Single(result.Links).Url);
    }

    [Fact]
    public void Public_dto_json_does_not_expose_persistence_or_internal_properties()
    {
        var dto = new PublicCatalogProductDto(
            Guid.NewGuid(),
            "public",
            "app",
            true,
            new LocalizedTextDto("Name", "الاسم"),
            new LocalizedTextDto("Short", "مختصر"),
            new LocalizedTextDto("Description", "الوصف"),
            null,
            [],
            [],
            null,
            [],
            []);

        var json = JsonSerializer.Serialize(dto, new JsonSerializerOptions(JsonSerializerDefaults.Web));

        Assert.DoesNotContain("publicationStatus", json, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("storageProvider", json, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("storageKey", json, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("sortOrder", json, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("releases", json, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task Mismatched_localized_json_collections_are_rejected()
    {
        await using var context = CreateContext();
        var product = Product.Create(Guid.NewGuid(), "invalid-localization", ProductCategory.App);
        product.SetLocalization("en", "Name", "Short", "Description", platforms: ["Web"]);
        product.SetLocalization("ar", "الاسم", "مختصر", "الوصف", platforms: []);
        product.Publish(DateTimeOffset.UtcNow);
        context.Add(product);
        await context.SaveChangesAsync();

        var exception = await Assert.ThrowsAsync<CatalogDataException>(() =>
            CreateQueries(context).GetProductAsync(ProductCategory.App, "invalid-localization"));

        Assert.Contains("mismatched English and Arabic platforms", exception.Message);
    }

    private static CatalogDbContext CreateContext()
    {
        var options = new DbContextOptionsBuilder<CatalogDbContext>()
            .UseInMemoryDatabase($"catalog-public-queries-{Guid.NewGuid()}")
            .Options;
        return new CatalogDbContext(options);
    }

    private static PublicCatalogQueries CreateQueries(
        CatalogDbContext context,
        IMediaUrlResolver? mediaUrlResolver = null) =>
        new(context, mediaUrlResolver ?? new PrefixMediaUrlResolver(null));

    private static Product CreateProduct(
        string slug,
        ProductCategory category,
        int sortOrder = 0,
        bool publish = false,
        bool featured = false)
    {
        var product = Product.Create(Guid.NewGuid(), slug, category, sortOrder);
        product.SetLocalization("en", $"{slug} EN", $"{slug} short EN", $"{slug} description EN");
        product.SetLocalization("ar", $"{slug} AR", $"{slug} short AR", $"{slug} description AR");
        if (publish)
        {
            product.Publish(DateTimeOffset.UtcNow, featured);
        }

        return product;
    }

    private sealed class PrefixMediaUrlResolver(string? prefix) : IMediaUrlResolver
    {
        public string? ResolvePublicUrl(string storageProvider, string storageKey) =>
            prefix is null ? null : $"{prefix}{storageKey}";
    }
}
