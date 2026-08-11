namespace BotGlobal.Catalog.Contracts.Admin;

public sealed record AdminCatalogProductsResponse(
    IReadOnlyCollection<AdminCatalogProductDto> Items,
    int Total);
