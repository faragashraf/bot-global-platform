namespace BotGlobal.Catalog.Contracts.Admin;

public sealed record AdminCatalogProductDetailDto(
    Guid Id,
    string Slug,
    string Category,
    string PublicationStatus,
    bool Featured,
    int SortOrder,
    DateTimeOffset? PublishedAtUtc,
    AdminCatalogProductLocalizationsDto Localizations,
    IReadOnlyList<AdminCatalogProductLinkDto> Links);

public sealed record AdminCatalogProductLocalizationsDto(
    AdminCatalogProductLocalizationDto En,
    AdminCatalogProductLocalizationDto Ar);

public sealed record AdminCatalogProductLocalizationDto(
    string Name,
    string ShortDescription,
    string Description,
    string? DisplayStatus,
    IReadOnlyList<string> Platforms,
    IReadOnlyList<string> Technologies);

public sealed record AdminCatalogProductLinkDto(
    Guid Id,
    string Type,
    string Url,
    string? LabelEn,
    string? LabelAr,
    int SortOrder);
