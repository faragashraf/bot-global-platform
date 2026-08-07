using System.Text.Json.Serialization;

namespace BotGlobal.Catalog.Contracts;

public sealed record PublicCatalogProductDto(
    Guid Id,
    string Slug,
    string Category,
    bool Featured,
    LocalizedTextDto Name,
    LocalizedTextDto ShortDescription,
    LocalizedTextDto Description,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] LocalizedTextDto? Status,
    IReadOnlyList<LocalizedTextDto> Platforms,
    IReadOnlyList<LocalizedTextDto> Technologies,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] CatalogMediaReferenceDto? HeroMedia,
    IReadOnlyList<CatalogMediaReferenceDto> Screenshots,
    IReadOnlyList<CatalogProductLinkDto> Links);

public sealed record LocalizedTextDto(string En, string Ar);

public sealed record CatalogMediaReferenceDto(
    string Url,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] LocalizedTextDto? Alt);

public sealed record CatalogProductLinkDto(
    string Type,
    string Url,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] LocalizedTextDto? Label);
