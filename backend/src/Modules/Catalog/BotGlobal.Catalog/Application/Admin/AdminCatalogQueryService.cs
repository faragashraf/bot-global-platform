using BotGlobal.Catalog.Contracts.Admin;
using BotGlobal.Catalog.Domain;
using BotGlobal.Catalog.Infrastructure;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Catalog.Application.Admin;

public sealed class AdminCatalogQueryService(
    CatalogDbContext dbContext)
    : IAdminCatalogQueryService
{
    public async Task<AdminCatalogProductDetailDto?> GetProductAsync(
        Guid id,
        CancellationToken cancellationToken = default)
    {
        var product = await dbContext.Products
            .AsNoTracking()
            .Include(candidate => candidate.Localizations)
            .Include(candidate => candidate.Links)
            .AsSplitQuery()
            .SingleOrDefaultAsync(candidate => candidate.Id == id, cancellationToken);

        return product is null
            ? null
            : AdminCatalogProductMapper.ToDetail(product);
    }

    public async Task<AdminCatalogProductsResponse> GetProductsAsync(
        string? search,
        ProductCategory? category,
        PublicationStatus? status,
        bool? featured,
        CancellationToken cancellationToken = default)
    {
        var query = dbContext.Products.AsNoTracking();

        if (!string.IsNullOrWhiteSpace(search))
        {
            var normalizedSearch = search.Trim().ToLower();
            query = query.Where(product =>
                product.Slug.ToLower().Contains(normalizedSearch) ||
                product.Localizations.Any(localization =>
                    localization.Name.ToLower().Contains(normalizedSearch)));
        }

        if (category.HasValue)
        {
            query = query.Where(product => product.Category == category.Value);
        }

        if (status.HasValue)
        {
            query = query.Where(product => product.PublicationStatus == status.Value);
        }

        if (featured.HasValue)
        {
            query = query.Where(product => product.IsFeatured == featured.Value);
        }

        var items = await query
            .OrderBy(product => product.SortOrder)
            .ThenBy(product => product.Category)
            .ThenBy(product => product.Slug)
            .ThenBy(product => product.Id)
            .Select(product => new AdminCatalogProductDto(
                product.Id,
                product.Slug,
                product.Category == ProductCategory.App
                    ? "app"
                    : product.Category == ProductCategory.Game
                        ? "game"
                        : "program",
                product.PublicationStatus == PublicationStatus.Draft
                    ? "Draft"
                    : product.PublicationStatus == PublicationStatus.Published
                        ? "Published"
                        : "Archived",
                product.IsFeatured,
                product.SortOrder,
                product.PublishedAtUtc,
                product.Localizations
                    .Where(localization => localization.Language == "en")
                    .Select(localization => localization.Name)
                    .FirstOrDefault() ?? string.Empty,
                product.Localizations
                    .Where(localization => localization.Language == "ar")
                    .Select(localization => localization.Name)
                    .FirstOrDefault() ?? string.Empty))
            .ToArrayAsync(cancellationToken);

        return new AdminCatalogProductsResponse(
            items,
            items.Length);
    }
}
