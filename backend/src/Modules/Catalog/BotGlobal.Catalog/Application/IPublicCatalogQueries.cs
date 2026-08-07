using BotGlobal.Catalog.Contracts;
using BotGlobal.Catalog.Domain;

namespace BotGlobal.Catalog.Application;

public interface IPublicCatalogQueries
{
    Task<IReadOnlyList<PublicCatalogProductDto>> GetProductsAsync(
        ProductCategory? category,
        bool? featured,
        CancellationToken cancellationToken = default);

    Task<PublicCatalogProductDto?> GetProductAsync(
        ProductCategory category,
        string slug,
        CancellationToken cancellationToken = default);
}
