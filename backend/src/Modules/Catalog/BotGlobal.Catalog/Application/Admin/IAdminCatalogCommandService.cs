using BotGlobal.Catalog.Contracts.Admin;

namespace BotGlobal.Catalog.Application.Admin;

public interface IAdminCatalogCommandService
{
    Task<AdminCatalogCommandResult> CreateAsync(
        CreateCatalogProductRequest? request,
        CancellationToken cancellationToken = default);

    Task<AdminCatalogCommandResult> UpdateAsync(
        Guid id,
        UpdateCatalogProductRequest? request,
        CancellationToken cancellationToken = default);
}
