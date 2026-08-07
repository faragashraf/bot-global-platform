using System.Text.RegularExpressions;

namespace BotGlobal.Catalog.Domain;

public sealed partial class Product
{
    private readonly List<ProductLocalization> _localizations = [];
    private readonly List<ProductMedia> _media = [];
    private readonly List<ProductLink> _links = [];
    private readonly List<ProductRelease> _releases = [];

    private Product()
    {
    }

    private Product(Guid id, string slug, ProductCategory category, int sortOrder)
    {
        if (id == Guid.Empty)
        {
            throw new CatalogDomainException("Product identifier is required.");
        }

        ValidateCategory(category);
        ValidateSortOrder(sortOrder);

        Id = id;
        Slug = ValidateSlug(slug);
        Category = category;
        PublicationStatus = PublicationStatus.Draft;
        SortOrder = sortOrder;
    }

    public Guid Id { get; private set; }
    public string Slug { get; private set; } = null!;
    public ProductCategory Category { get; private set; }
    public PublicationStatus PublicationStatus { get; private set; }
    public bool IsFeatured { get; private set; }
    public int SortOrder { get; private set; }
    public DateTimeOffset? PublishedAtUtc { get; private set; }
    public bool CanBePhysicallyDeleted => PublicationStatus == PublicationStatus.Draft;
    public IReadOnlyCollection<ProductLocalization> Localizations => _localizations.AsReadOnly();
    public IReadOnlyCollection<ProductMedia> Media => _media.AsReadOnly();
    public IReadOnlyCollection<ProductLink> Links => _links.AsReadOnly();
    public IReadOnlyCollection<ProductRelease> Releases => _releases.AsReadOnly();

    public static Product Create(Guid id, string slug, ProductCategory category, int sortOrder = 0) =>
        new(id, slug, category, sortOrder);

    public void ChangeSlug(string slug)
    {
        EnsureIdentityIsMutable();
        Slug = ValidateSlug(slug);
    }

    public void ChangeCategory(ProductCategory category)
    {
        EnsureIdentityIsMutable();
        ValidateCategory(category);
        Category = category;
    }

    public void SetSortOrder(int sortOrder)
    {
        ValidateSortOrder(sortOrder);
        SortOrder = sortOrder;
    }

    public void SetLocalization(
        string language,
        string name,
        string shortDescription,
        string description,
        string? displayStatus = null,
        IEnumerable<string>? platforms = null,
        IEnumerable<string>? technologies = null)
    {
        language = ProductLocalization.ValidateLanguage(language);
        var localization = _localizations.SingleOrDefault(item => item.Language == language);

        if (localization is null)
        {
            _localizations.Add(new ProductLocalization(
                Id,
                language,
                name,
                shortDescription,
                description,
                displayStatus,
                platforms ?? [],
                technologies ?? []));
            return;
        }

        localization.ReplaceContent(
            name,
            shortDescription,
            description,
            displayStatus,
            platforms ?? [],
            technologies ?? []);
    }

    public void ReplaceLinks(IEnumerable<ProductLink> links)
    {
        ArgumentNullException.ThrowIfNull(links);
        var replacements = links.ToArray();

        if (replacements.Any(link => link.ProductId != Id))
        {
            throw new CatalogDomainException("Every link must belong to this product.");
        }

        if (replacements
            .GroupBy(link => new { link.Type, link.Url })
            .Any(group => group.Count() > 1))
        {
            throw new CatalogDomainException("Product links must be unique by type and URL.");
        }

        _links.Clear();
        _links.AddRange(replacements);
    }

    public void AddMedia(ProductMedia media)
    {
        ArgumentNullException.ThrowIfNull(media);
        if (media.ProductId != Id)
        {
            throw new CatalogDomainException("Media must belong to this product.");
        }

        if (media.Kind == ProductMediaKind.Hero && _media.Any(item => item.Kind == ProductMediaKind.Hero))
        {
            throw new CatalogDomainException("A product can have only one hero image.");
        }

        _media.Add(media);
    }

    public void AddRelease(ProductRelease release)
    {
        ArgumentNullException.ThrowIfNull(release);
        if (release.ProductId != Id)
        {
            throw new CatalogDomainException("Release must belong to this product.");
        }

        if (_releases.Any(item => item.Version == release.Version))
        {
            throw new CatalogDomainException("Release versions must be unique per product.");
        }

        _releases.Add(release);
    }

    public void Publish(DateTimeOffset publishedAtUtc, bool isFeatured = false)
    {
        if (PublicationStatus != PublicationStatus.Draft)
        {
            throw new CatalogDomainException("Only draft products can be published.");
        }

        if (!_localizations.Any(item => item.Language == "en") ||
            !_localizations.Any(item => item.Language == "ar"))
        {
            throw new CatalogDomainException("Published products require English and Arabic localizations.");
        }

        PublicationStatus = PublicationStatus.Published;
        PublishedAtUtc = publishedAtUtc;
        IsFeatured = isFeatured;
    }

    public void Archive()
    {
        if (PublicationStatus != PublicationStatus.Published)
        {
            throw new CatalogDomainException("Only published products can be archived.");
        }

        PublicationStatus = PublicationStatus.Archived;
        IsFeatured = false;
    }

    public void SetFeatured(bool isFeatured)
    {
        if (isFeatured && PublicationStatus != PublicationStatus.Published)
        {
            throw new CatalogDomainException("Only published products can be featured.");
        }

        IsFeatured = isFeatured;
    }

    private void EnsureIdentityIsMutable()
    {
        if (PublicationStatus != PublicationStatus.Draft || PublishedAtUtc.HasValue)
        {
            throw new CatalogDomainException("Product category and slug are immutable after first publication.");
        }
    }

    private static string ValidateSlug(string slug)
    {
        if (string.IsNullOrWhiteSpace(slug) || slug.Length > 100 || !SlugPattern().IsMatch(slug))
        {
            throw new CatalogDomainException("Slug must be lowercase kebab-case and no longer than 100 characters.");
        }

        return slug;
    }

    private static void ValidateCategory(ProductCategory category)
    {
        if (!Enum.IsDefined(category))
        {
            throw new CatalogDomainException("Product category is invalid.");
        }
    }

    private static void ValidateSortOrder(int sortOrder)
    {
        if (sortOrder < 0)
        {
            throw new CatalogDomainException("Product sort order cannot be negative.");
        }
    }

    [GeneratedRegex("^[a-z0-9]+(?:-[a-z0-9]+)*$", RegexOptions.CultureInvariant)]
    private static partial Regex SlugPattern();
}
