using BotGlobal.Catalog.Contracts.Admin;
using BotGlobal.Catalog.Domain;

namespace BotGlobal.Catalog.Application.Admin;

internal static class AdminCatalogProductMapper
{
    public static AdminCatalogProductDetailDto ToDetail(Product product)
    {
        var english = Localization(product, "en");
        var arabic = Localization(product, "ar");

        return new AdminCatalogProductDetailDto(
            product.Id,
            product.Slug,
            CategoryValue(product.Category),
            product.PublicationStatus.ToString(),
            product.IsFeatured,
            product.SortOrder,
            product.PublishedAtUtc,
            new AdminCatalogProductLocalizationsDto(
                ToDto(english),
                ToDto(arabic)),
            product.Links
                .OrderBy(link => link.SortOrder)
                .ThenBy(link => link.Type)
                .ThenBy(link => link.Url)
                .Select(link => new AdminCatalogProductLinkDto(
                    link.Id,
                    LinkTypeValue(link.Type),
                    link.Url,
                    link.LabelEn,
                    link.LabelAr,
                    link.SortOrder))
                .ToArray());
    }

    private static ProductLocalization Localization(Product product, string language) =>
        product.Localizations.Single(localization => localization.Language == language);

    private static AdminCatalogProductLocalizationDto ToDto(ProductLocalization localization) =>
        new(
            localization.Name,
            localization.ShortDescription,
            localization.Description,
            localization.DisplayStatus,
            localization.Platforms,
            localization.Technologies);

    private static string CategoryValue(ProductCategory category) => category switch
    {
        ProductCategory.App => "app",
        ProductCategory.Game => "game",
        ProductCategory.Program => "program",
        _ => throw new CatalogDataException($"Catalog product category '{category}' is invalid.")
    };

    private static string LinkTypeValue(ProductLinkType type) => type switch
    {
        ProductLinkType.Support => "support",
        ProductLinkType.Privacy => "privacy",
        ProductLinkType.Store => "store",
        ProductLinkType.Download => "download",
        ProductLinkType.Website => "website",
        _ => throw new CatalogDataException($"Catalog product link type '{type}' is invalid.")
    };
}
