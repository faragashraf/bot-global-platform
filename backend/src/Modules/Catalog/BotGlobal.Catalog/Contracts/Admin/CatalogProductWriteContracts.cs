namespace BotGlobal.Catalog.Contracts.Admin;

public interface IAdminCatalogProductWriteRequest
{
    string Slug { get; }
    string Category { get; }
    bool Featured { get; }
    int SortOrder { get; }
    CatalogProductLocalizationsRequest Localizations { get; }
    IReadOnlyList<CatalogProductLinkRequest> Links { get; }
}

public sealed record CreateCatalogProductRequest(
    string Slug,
    string Category,
    bool Featured,
    int SortOrder,
    CatalogProductLocalizationsRequest Localizations,
    IReadOnlyList<CatalogProductLinkRequest> Links)
    : IAdminCatalogProductWriteRequest;

public sealed record UpdateCatalogProductRequest(
    string Slug,
    string Category,
    bool Featured,
    int SortOrder,
    CatalogProductLocalizationsRequest Localizations,
    IReadOnlyList<CatalogProductLinkRequest> Links)
    : IAdminCatalogProductWriteRequest;

public sealed record CatalogProductLocalizationsRequest(
    CatalogProductLocalizationRequest En,
    CatalogProductLocalizationRequest Ar);

public sealed record CatalogProductLocalizationRequest(
    string Name,
    string ShortDescription,
    string Description,
    string? DisplayStatus,
    IReadOnlyList<string> Platforms,
    IReadOnlyList<string> Technologies);

public sealed record CatalogProductLinkRequest(
    string Type,
    string Url,
    string? LabelEn,
    string? LabelAr,
    int SortOrder);
