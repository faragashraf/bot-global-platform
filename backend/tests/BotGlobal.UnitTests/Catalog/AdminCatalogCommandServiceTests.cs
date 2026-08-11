using BotGlobal.Catalog.Application.Admin;
using BotGlobal.Catalog.Contracts.Admin;
using BotGlobal.Catalog.Domain;
using BotGlobal.Catalog.Infrastructure;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.UnitTests.Catalog;

public sealed class AdminCatalogCommandServiceTests
{
    [Fact]
    public async Task Create_stores_a_draft_with_bilingual_content_and_links()
    {
        await using var context = CreateContext();
        var request = CreateRequest("new-product", links: [ValidLink()]);

        var result = await new AdminCatalogCommandService(context).CreateAsync(request);

        Assert.Null(result.Failure);
        Assert.NotNull(result.Product);
        Assert.Equal("Draft", result.Product.PublicationStatus);
        Assert.Null(result.Product.PublishedAtUtc);
        Assert.False(result.Product.Featured);

        var product = await context.Products
            .Include(candidate => candidate.Localizations)
            .Include(candidate => candidate.Links)
            .SingleAsync();
        Assert.Equal(PublicationStatus.Draft, product.PublicationStatus);
        Assert.Null(product.PublishedAtUtc);
        Assert.Equal("English name", product.Localizations.Single(item => item.Language == "en").Name);
        Assert.Equal("الاسم العربي", product.Localizations.Single(item => item.Language == "ar").Name);
        Assert.Equal("https://example.com/support", Assert.Single(product.Links).Url.TrimEnd('/'));
    }

    [Fact]
    public async Task Create_returns_conflict_for_duplicate_category_and_slug()
    {
        await using var context = CreateContext();
        context.Add(CreateDomainProduct("duplicate", ProductCategory.App));
        await context.SaveChangesAsync();

        var result = await new AdminCatalogCommandService(context)
            .CreateAsync(CreateRequest("duplicate"));

        Assert.Equal(AdminCatalogCommandFailureKind.Conflict, result.Failure?.Kind);
        Assert.Single(context.Products);
    }

    [Theory]
    [InlineData("Invalid Slug", false)]
    [InlineData("valid-slug", true)]
    public async Task Create_rejects_domain_invalid_draft_data(string slug, bool featured)
    {
        await using var context = CreateContext();

        var result = await new AdminCatalogCommandService(context)
            .CreateAsync(CreateRequest(slug, featured));

        Assert.Equal(AdminCatalogCommandFailureKind.Validation, result.Failure?.Kind);
        Assert.Empty(context.Products);
    }

    [Fact]
    public async Task Create_rejects_malformed_and_duplicate_links()
    {
        await using var malformedContext = CreateContext();
        var malformed = await new AdminCatalogCommandService(malformedContext).CreateAsync(
            CreateRequest("malformed-link", links: [ValidLink() with { Url = "not-a-url" }]));

        await using var duplicateContext = CreateContext();
        var duplicateLink = ValidLink();
        var duplicate = await new AdminCatalogCommandService(duplicateContext).CreateAsync(
            CreateRequest("duplicate-links", links: [duplicateLink, duplicateLink]));

        Assert.Equal(AdminCatalogCommandFailureKind.Validation, malformed.Failure?.Kind);
        Assert.Equal(AdminCatalogCommandFailureKind.Validation, duplicate.Failure?.Kind);
        Assert.Empty(malformedContext.Products);
        Assert.Empty(duplicateContext.Products);
    }

    [Fact]
    public async Task Update_replaces_draft_content_and_links_without_changing_lifecycle()
    {
        await using var context = CreateContext();
        var product = CreateDomainProduct("old-product", ProductCategory.App);
        product.ReplaceLinks([
            new ProductLink(
                Guid.NewGuid(),
                product.Id,
                ProductLinkType.Support,
                "https://example.com/old",
                0)
        ]);
        context.Add(product);
        await context.SaveChangesAsync();
        var originalStatus = product.PublicationStatus;
        var originalPublishedAt = product.PublishedAtUtc;
        context.ChangeTracker.Clear();

        var result = await new AdminCatalogCommandService(context).UpdateAsync(
            product.Id,
            UpdateRequest("updated-product", ProductCategory.Game, [ValidLink()]));

        Assert.Null(result.Failure);
        var updated = await context.Products
            .Include(candidate => candidate.Localizations)
            .Include(candidate => candidate.Links)
            .SingleAsync();
        Assert.Equal("updated-product", updated.Slug);
        Assert.Equal(ProductCategory.Game, updated.Category);
        Assert.Equal(7, updated.SortOrder);
        Assert.Equal("Updated English", updated.Localizations.Single(item => item.Language == "en").Name);
        Assert.Equal("العربية المحدثة", updated.Localizations.Single(item => item.Language == "ar").Name);
        Assert.Equal("https://example.com/support", Assert.Single(updated.Links).Url.TrimEnd('/'));
        Assert.Equal(originalStatus, updated.PublicationStatus);
        Assert.Equal(originalPublishedAt, updated.PublishedAtUtc);
    }

    [Fact]
    public async Task Update_returns_not_found_for_missing_product()
    {
        await using var context = CreateContext();

        var result = await new AdminCatalogCommandService(context)
            .UpdateAsync(Guid.NewGuid(), UpdateRequest("missing", ProductCategory.App));

        Assert.Equal(AdminCatalogCommandFailureKind.NotFound, result.Failure?.Kind);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Update_rejects_published_and_archived_products(bool archive)
    {
        await using var context = CreateContext();
        var product = CreateDomainProduct("lifecycle-product", ProductCategory.App);
        product.Publish(DateTimeOffset.UtcNow);
        if (archive)
        {
            product.Archive();
        }

        context.Add(product);
        await context.SaveChangesAsync();
        var status = product.PublicationStatus;

        var result = await new AdminCatalogCommandService(context).UpdateAsync(
            product.Id,
            UpdateRequest("changed", ProductCategory.Game));

        Assert.Equal(AdminCatalogCommandFailureKind.Conflict, result.Failure?.Kind);
        Assert.Equal("lifecycle-product", product.Slug);
        Assert.Equal(status, product.PublicationStatus);
    }

    private static CatalogDbContext CreateContext()
    {
        var options = new DbContextOptionsBuilder<CatalogDbContext>()
            .UseInMemoryDatabase($"catalog-admin-commands-{Guid.NewGuid()}")
            .Options;
        return new CatalogDbContext(options);
    }

    private static CreateCatalogProductRequest CreateRequest(
        string slug,
        bool featured = false,
        IReadOnlyList<CatalogProductLinkRequest>? links = null) =>
        new(
            slug,
            "app",
            featured,
            3,
            Localizations("English name", "الاسم العربي"),
            links ?? []);

    private static UpdateCatalogProductRequest UpdateRequest(
        string slug,
        ProductCategory category,
        IReadOnlyList<CatalogProductLinkRequest>? links = null) =>
        new(
            slug,
            category.ToString().ToLowerInvariant(),
            false,
            7,
            Localizations("Updated English", "العربية المحدثة"),
            links ?? []);

    private static CatalogProductLocalizationsRequest Localizations(
        string englishName,
        string arabicName) =>
        new(
            new CatalogProductLocalizationRequest(
                englishName,
                "English short description",
                "English description",
                "Draft",
                ["Web", "Windows"],
                [".NET", "Angular"]),
            new CatalogProductLocalizationRequest(
                arabicName,
                "وصف عربي مختصر",
                "الوصف العربي",
                "مسودة",
                ["الويب", "ويندوز"],
                ["دوت نت", "أنجولار"]));

    private static CatalogProductLinkRequest ValidLink() =>
        new("support", "https://example.com/support", "Support", "الدعم", 1);

    private static Product CreateDomainProduct(string slug, ProductCategory category)
    {
        var product = Product.Create(Guid.NewGuid(), slug, category);
        product.SetLocalization("en", "English", "English short", "English description");
        product.SetLocalization("ar", "العربية", "وصف مختصر", "الوصف العربي");
        return product;
    }
}
