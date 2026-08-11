namespace BotGlobal.Catalog.Contracts.Admin;

public sealed record AdminCatalogProductDto(
    Guid Id,
    string Slug,
    string Category,
    string PublicationStatus,
    bool Featured,
    int SortOrder,
    DateTimeOffset? PublishedAtUtc,
    string NameEn,
    string NameAr);
