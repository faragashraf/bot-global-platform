using BotGlobal.Catalog.Contracts;
using BotGlobal.Catalog.Domain;
using BotGlobal.Catalog.Infrastructure;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Catalog.Application;

public sealed class PublicCatalogQueries(
    CatalogDbContext dbContext,
    IMediaUrlResolver mediaUrlResolver) : IPublicCatalogQueries
{
    public async Task<IReadOnlyList<PublicCatalogProductDto>> GetProductsAsync(
        ProductCategory? category,
        bool? featured,
        CancellationToken cancellationToken = default)
    {
        var query = PublishedProducts();

        if (category.HasValue)
        {
            query = query.Where(product => product.Category == category.Value);
        }

        if (featured.HasValue)
        {
            query = query.Where(product => product.IsFeatured == featured.Value);
        }

        var products = await Project(query
                .OrderBy(product => product.SortOrder)
                .ThenBy(product => product.Slug))
            .ToListAsync(cancellationToken);

        return products.Select(MapProduct).ToArray();
    }

    public async Task<PublicCatalogProductDto?> GetProductAsync(
        ProductCategory category,
        string slug,
        CancellationToken cancellationToken = default)
    {
        var product = await Project(PublishedProducts()
                .Where(candidate => candidate.Category == category && candidate.Slug == slug))
            .SingleOrDefaultAsync(cancellationToken);

        return product is null ? null : MapProduct(product);
    }

    private IQueryable<Product> PublishedProducts() =>
        dbContext.Products
            .AsNoTracking()
            .Where(product => product.PublicationStatus == PublicationStatus.Published);

    private static IQueryable<ProductReadModel> Project(IQueryable<Product> query) =>
        query
            .AsSplitQuery()
            .Select(product => new ProductReadModel(
                product.Id,
                product.Slug,
                product.Category,
                product.IsFeatured,
                product.Localizations
                    .Select(localization => new LocalizationReadModel(
                        localization.Language,
                        localization.Name,
                        localization.ShortDescription,
                        localization.Description,
                        localization.DisplayStatus,
                        localization.Platforms,
                        localization.Technologies))
                    .ToArray(),
                product.Media
                    .OrderBy(media => media.SortOrder)
                    .Select(media => new MediaReadModel(
                        media.Kind,
                        media.StorageProvider,
                        media.StorageKey,
                        media.AltTextEn,
                        media.AltTextAr))
                    .ToArray(),
                product.Links
                    .OrderBy(link => link.SortOrder)
                    .Select(link => new LinkReadModel(
                        link.Type,
                        link.Url,
                        link.LabelEn,
                        link.LabelAr))
                    .ToArray()));

    private PublicCatalogProductDto MapProduct(ProductReadModel product)
    {
        var localizations = product.Localizations.ToDictionary(
            localization => localization.Language,
            StringComparer.Ordinal);

        if (!localizations.TryGetValue("en", out var english) ||
            !localizations.TryGetValue("ar", out var arabic) ||
            localizations.Count != 2)
        {
            throw new CatalogDataException(
                $"Published catalog product '{product.Id}' must contain exactly English and Arabic localizations.");
        }

        var media = product.Media
            .Select(item => new { Item = item, Url = mediaUrlResolver.ResolvePublicUrl(item.StorageProvider, item.StorageKey) })
            .Where(item => !string.IsNullOrWhiteSpace(item.Url))
            .Select(item => new ResolvedMediaReadModel(item.Item.Kind, item.Url!, MapOptionalPair(
                item.Item.AltTextEn,
                item.Item.AltTextAr,
                product.Id,
                "media alt text")))
            .ToArray();

        var heroMedia = media.FirstOrDefault(item => item.Kind == ProductMediaKind.Hero)?.Reference;
        var screenshots = media
            .Where(item => item.Kind == ProductMediaKind.Screenshot)
            .Select(item => item.Reference)
            .ToArray();

        return new PublicCatalogProductDto(
            product.Id,
            product.Slug,
            CategoryValue(product.Category),
            product.IsFeatured,
            new LocalizedTextDto(english.Name, arabic.Name),
            new LocalizedTextDto(english.ShortDescription, arabic.ShortDescription),
            new LocalizedTextDto(english.Description, arabic.Description),
            MapOptionalPair(english.DisplayStatus, arabic.DisplayStatus, product.Id, "display status"),
            MapLocalizedCollection(english.Platforms, arabic.Platforms, product.Id, "platforms"),
            MapLocalizedCollection(english.Technologies, arabic.Technologies, product.Id, "technologies"),
            heroMedia,
            screenshots,
            product.Links.Select(link => MapLink(link, product.Id)).ToArray());
    }

    private static CatalogProductLinkDto MapLink(LinkReadModel link, Guid productId)
    {
        if (!Uri.TryCreate(link.Url, UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https"))
        {
            throw new CatalogDataException(
                $"Catalog product '{productId}' contains a link without a valid public HTTP or HTTPS URL.");
        }

        return new CatalogProductLinkDto(
            LinkTypeValue(link.Type),
            uri.AbsoluteUri,
            MapOptionalPair(link.LabelEn, link.LabelAr, productId, "link label"));
    }

    private static IReadOnlyList<LocalizedTextDto> MapLocalizedCollection(
        IReadOnlyList<string> english,
        IReadOnlyList<string> arabic,
        Guid productId,
        string fieldName)
    {
        if (english.Count != arabic.Count)
        {
            throw new CatalogDataException(
                $"Catalog product '{productId}' has mismatched English and Arabic {fieldName}.");
        }

        return english
            .Select((value, index) => new LocalizedTextDto(value, arabic[index]))
            .ToArray();
    }

    private static LocalizedTextDto? MapOptionalPair(
        string? english,
        string? arabic,
        Guid productId,
        string fieldName)
    {
        if (english is null && arabic is null)
        {
            return null;
        }

        if (string.IsNullOrWhiteSpace(english) || string.IsNullOrWhiteSpace(arabic))
        {
            throw new CatalogDataException(
                $"Catalog product '{productId}' has incomplete bilingual {fieldName}.");
        }

        return new LocalizedTextDto(english, arabic);
    }

    private static string CategoryValue(ProductCategory category) => category switch
    {
        ProductCategory.App => "app",
        ProductCategory.Game => "game",
        ProductCategory.Program => "program",
        _ => throw new CatalogDataException($"Catalog product category '{category}' is not supported publicly.")
    };

    private static string LinkTypeValue(ProductLinkType type) => type switch
    {
        ProductLinkType.Support => "support",
        ProductLinkType.Privacy => "privacy",
        ProductLinkType.Store => "store",
        ProductLinkType.Download => "download",
        ProductLinkType.Website => "website",
        _ => throw new CatalogDataException($"Catalog product link type '{type}' is not supported publicly.")
    };

    private sealed record ProductReadModel(
        Guid Id,
        string Slug,
        ProductCategory Category,
        bool IsFeatured,
        IReadOnlyList<LocalizationReadModel> Localizations,
        IReadOnlyList<MediaReadModel> Media,
        IReadOnlyList<LinkReadModel> Links);

    private sealed record LocalizationReadModel(
        string Language,
        string Name,
        string ShortDescription,
        string Description,
        string? DisplayStatus,
        IReadOnlyList<string> Platforms,
        IReadOnlyList<string> Technologies);

    private sealed record MediaReadModel(
        ProductMediaKind Kind,
        string StorageProvider,
        string StorageKey,
        string? AltTextEn,
        string? AltTextAr);

    private sealed record ResolvedMediaReadModel(
        ProductMediaKind Kind,
        CatalogMediaReferenceDto Reference)
    {
        public ResolvedMediaReadModel(ProductMediaKind kind, string url, LocalizedTextDto? alt)
            : this(kind, new CatalogMediaReferenceDto(url, alt))
        {
        }
    }

    private sealed record LinkReadModel(
        ProductLinkType Type,
        string Url,
        string? LabelEn,
        string? LabelAr);
}
