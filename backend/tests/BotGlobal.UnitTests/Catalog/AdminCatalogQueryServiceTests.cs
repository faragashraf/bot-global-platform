using BotGlobal.Catalog.Application.Admin;
using BotGlobal.Catalog.Domain;
using BotGlobal.Catalog.Infrastructure;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.UnitTests.Catalog;

public sealed class AdminCatalogQueryServiceTests
{
    [Fact]
    public async Task List_includes_every_publication_status_in_deterministic_order()
    {
        await using var context = CreateContext();
        var draft = CreateProduct("alpha", ProductCategory.App, sortOrder: 2);
        var published = CreateProduct("bravo", ProductCategory.Game, sortOrder: 1, publish: true);
        var archived = CreateProduct("charlie", ProductCategory.Program, sortOrder: 1, publish: true);
        archived.Archive();
        context.AddRange(draft, published, archived);
        await context.SaveChangesAsync();
        context.ChangeTracker.Clear();

        var result = await new AdminCatalogQueryService(context)
            .GetProductsAsync(null, null, null, null);

        Assert.Equal(["bravo", "charlie", "alpha"], result.Items.Select(item => item.Slug));
        Assert.Equal(["Published", "Archived", "Draft"], result.Items.Select(item => item.PublicationStatus));
        Assert.Equal(3, result.Total);
        Assert.Empty(context.ChangeTracker.Entries());
    }

    [Fact]
    public async Task List_applies_search_category_status_and_featured_filters()
    {
        await using var context = CreateContext();
        context.AddRange(
            CreateProduct("sentricam", ProductCategory.App, publish: true, featured: true),
            CreateProduct("other-app", ProductCategory.App, publish: true),
            CreateProduct("sentri-game", ProductCategory.Game, publish: true, featured: true));
        await context.SaveChangesAsync();

        var result = await new AdminCatalogQueryService(context).GetProductsAsync(
            "  SENTRICAM EN  ",
            ProductCategory.App,
            PublicationStatus.Published,
            true);

        var product = Assert.Single(result.Items);
        Assert.Equal("sentricam", product.Slug);
        Assert.Equal("sentricam EN", product.NameEn);
        Assert.Equal("sentricam AR", product.NameAr);
        Assert.Equal("app", product.Category);
        Assert.True(product.Featured);
    }

    [Fact]
    public async Task Detail_returns_complete_bilingual_authoring_data()
    {
        await using var context = CreateContext();
        var product = CreateProduct("draft-detail", ProductCategory.Program);
        product.ReplaceLinks([
            new ProductLink(
                Guid.NewGuid(),
                product.Id,
                ProductLinkType.Website,
                "https://example.com/product",
                2,
                "Website",
                "الموقع")
        ]);
        context.Add(product);
        await context.SaveChangesAsync();
        context.ChangeTracker.Clear();

        var result = await new AdminCatalogQueryService(context)
            .GetProductAsync(product.Id);

        Assert.NotNull(result);
        Assert.Equal("draft-detail EN", result.Localizations.En.Name);
        Assert.Equal("draft-detail AR", result.Localizations.Ar.Name);
        Assert.Equal("website", Assert.Single(result.Links).Type);
        Assert.Equal("Draft", result.PublicationStatus);
        Assert.Empty(context.ChangeTracker.Entries());
    }

    private static CatalogDbContext CreateContext()
    {
        var options = new DbContextOptionsBuilder<CatalogDbContext>()
            .UseInMemoryDatabase($"catalog-admin-queries-{Guid.NewGuid()}")
            .Options;
        return new CatalogDbContext(options);
    }

    private static Product CreateProduct(
        string slug,
        ProductCategory category,
        int sortOrder = 0,
        bool publish = false,
        bool featured = false)
    {
        var product = Product.Create(Guid.NewGuid(), slug, category, sortOrder);
        product.SetLocalization("en", $"{slug} EN", "Short EN", "Description EN");
        product.SetLocalization("ar", $"{slug} AR", "Short AR", "Description AR");

        if (publish)
        {
            product.Publish(DateTimeOffset.UtcNow, featured);
        }

        return product;
    }
}
