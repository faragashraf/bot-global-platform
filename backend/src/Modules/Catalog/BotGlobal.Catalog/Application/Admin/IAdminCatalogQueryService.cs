using BotGlobal.Catalog.Contracts.Admin;
using BotGlobal.Catalog.Domain;

namespace BotGlobal.Catalog.Application.Admin;

public interface IAdminCatalogQueryService
{
    Task<AdminCatalogProductDetailDto?> GetProductAsync(
        Guid id,
        CancellationToken cancellationToken = default);

    Task<AdminCatalogProductsResponse> GetProductsAsync(
        string? search,
        ProductCategory? category,
        PublicationStatus? status,
        bool? featured,
        CancellationToken cancellationToken = default);
}
