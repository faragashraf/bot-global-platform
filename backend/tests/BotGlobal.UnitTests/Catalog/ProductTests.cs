using BotGlobal.Catalog.Domain;

namespace BotGlobal.UnitTests.Catalog;

public sealed class ProductTests
{
    [Theory]
    [InlineData("")]
    [InlineData("SentriCam")]
    [InlineData("sentri_cam")]
    [InlineData("sentri cam")]
    [InlineData("-sentricam")]
    [InlineData("sentricam-")]
    [InlineData("sentri--cam")]
    public void Create_rejects_invalid_slugs(string slug)
    {
        Assert.Throws<CatalogDomainException>(() =>
            Product.Create(Guid.NewGuid(), slug, ProductCategory.App));
    }

    [Fact]
    public void Create_rejects_unknown_category()
    {
        Assert.Throws<CatalogDomainException>(() =>
            Product.Create(Guid.NewGuid(), "sentricam", (ProductCategory)999));
    }

    [Fact]
    public void Publish_requires_english_and_arabic_localizations()
    {
        var product = Product.Create(Guid.NewGuid(), "sentricam", ProductCategory.App);
        SetLocalization(product, "en", "English");

        var exception = Assert.Throws<CatalogDomainException>(() =>
            product.Publish(DateTimeOffset.UtcNow));

        Assert.Contains("English and Arabic", exception.Message);
        Assert.Equal(PublicationStatus.Draft, product.PublicationStatus);
    }

    [Fact]
    public void Category_and_slug_are_immutable_after_first_publication()
    {
        var product = PublishedProduct();

        Assert.Throws<CatalogDomainException>(() => product.ChangeSlug("new-slug"));
        Assert.Throws<CatalogDomainException>(() => product.ChangeCategory(ProductCategory.Game));

        product.Archive();

        Assert.Throws<CatalogDomainException>(() => product.ChangeSlug("archived-slug"));
        Assert.Throws<CatalogDomainException>(() => product.ChangeCategory(ProductCategory.Program));
    }

    [Fact]
    public void Archive_is_a_published_only_terminal_lifecycle_transition()
    {
        var draft = Product.Create(Guid.NewGuid(), "draft-product", ProductCategory.Program);
        Assert.Throws<CatalogDomainException>(draft.Archive);

        var product = PublishedProduct(isFeatured: true);
        product.Archive();

        Assert.Equal(PublicationStatus.Archived, product.PublicationStatus);
        Assert.False(product.IsFeatured);
        Assert.False(product.CanBePhysicallyDeleted);
        Assert.Throws<CatalogDomainException>(() => product.Publish(DateTimeOffset.UtcNow));
        Assert.Throws<CatalogDomainException>(product.Archive);
    }

    [Fact]
    public void Only_drafts_are_eligible_for_physical_delete()
    {
        var draft = Product.Create(Guid.NewGuid(), "draft-product", ProductCategory.App);
        Assert.True(draft.CanBePhysicallyDeleted);

        SetLocalization(draft, "en", "English");
        SetLocalization(draft, "ar", "Arabic");
        draft.Publish(DateTimeOffset.UtcNow);

        Assert.False(draft.CanBePhysicallyDeleted);
    }

    [Fact]
    public void Featured_is_allowed_only_for_published_products()
    {
        var product = Product.Create(Guid.NewGuid(), "sentricam", ProductCategory.App);

        Assert.Throws<CatalogDomainException>(() => product.SetFeatured(true));

        SetLocalization(product, "en", "English");
        SetLocalization(product, "ar", "Arabic");
        product.Publish(DateTimeOffset.UtcNow);
        product.SetFeatured(true);

        Assert.True(product.IsFeatured);
    }

    [Fact]
    public void SetLocalization_replaces_existing_language_content_and_collections()
    {
        var product = Product.Create(Guid.NewGuid(), "sentricam", ProductCategory.App);
        product.SetLocalization(
            "en",
            "Old name",
            "Old short description",
            "Old description",
            "Old status",
            ["Windows"],
            [".NET"]);
        var original = Assert.Single(product.Localizations);

        product.SetLocalization(
            "en",
            "New name",
            "New short description",
            "New description",
            null,
            ["iOS", "iOS", "Android"],
            []);

        var replacement = Assert.Single(product.Localizations);
        Assert.Same(original, replacement);
        Assert.Equal("New name", replacement.Name);
        Assert.Null(replacement.DisplayStatus);
        Assert.Equal(["iOS", "Android"], replacement.Platforms);
        Assert.Empty(replacement.Technologies);
    }

    [Fact]
    public void ReplaceLinks_replaces_the_collection_and_rejects_duplicates()
    {
        var product = Product.Create(Guid.NewGuid(), "sentricam", ProductCategory.App);
        var support = new ProductLink(
            Guid.NewGuid(), product.Id, ProductLinkType.Support, "https://example.com/support", 0);

        product.ReplaceLinks([support]);
        Assert.Same(support, Assert.Single(product.Links));

        var duplicate = new ProductLink(
            Guid.NewGuid(), product.Id, ProductLinkType.Support, "https://example.com/support", 1);
        Assert.Throws<CatalogDomainException>(() => product.ReplaceLinks([support, duplicate]));
    }

    private static Product PublishedProduct(bool isFeatured = false)
    {
        var product = Product.Create(Guid.NewGuid(), "sentricam", ProductCategory.App);
        SetLocalization(product, "en", "English");
        SetLocalization(product, "ar", "Arabic");
        product.Publish(DateTimeOffset.UtcNow, isFeatured);
        return product;
    }

    private static void SetLocalization(Product product, string language, string name) =>
        product.SetLocalization(
            language,
            name,
            $"{name} short description",
            $"{name} description");
}
