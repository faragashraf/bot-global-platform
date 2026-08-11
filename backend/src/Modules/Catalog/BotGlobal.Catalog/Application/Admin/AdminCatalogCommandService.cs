using BotGlobal.Catalog.Contracts.Admin;
using BotGlobal.Catalog.Domain;
using BotGlobal.Catalog.Infrastructure;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Catalog.Application.Admin;

public sealed class AdminCatalogCommandService(CatalogDbContext dbContext)
    : IAdminCatalogCommandService
{
    public async Task<AdminCatalogCommandResult> CreateAsync(
        CreateCatalogProductRequest? request,
        CancellationToken cancellationToken = default)
    {
        if (InvalidShape(request, out var shapeError))
        {
            return AdminCatalogCommandResult.Failed(
                AdminCatalogCommandFailureKind.InvalidRequest,
                shapeError);
        }

        try
        {
            var product = CreateProduct(Guid.NewGuid(), request!);
            if (await HasDuplicateIdentityAsync(product, null, cancellationToken))
            {
                return DuplicateIdentity();
            }

            dbContext.Products.Add(product);
            await dbContext.SaveChangesAsync(cancellationToken);
            return AdminCatalogCommandResult.Success(
                AdminCatalogProductMapper.ToDetail(product));
        }
        catch (CatalogDomainException exception)
        {
            return DomainValidation(exception);
        }
        catch (DbUpdateException exception) when (IsUniqueConstraintViolation(exception))
        {
            return DuplicateIdentity();
        }
    }

    public async Task<AdminCatalogCommandResult> UpdateAsync(
        Guid id,
        UpdateCatalogProductRequest? request,
        CancellationToken cancellationToken = default)
    {
        if (InvalidShape(request, out var shapeError))
        {
            return AdminCatalogCommandResult.Failed(
                AdminCatalogCommandFailureKind.InvalidRequest,
                shapeError);
        }

        var product = await dbContext.Products
            .Include(candidate => candidate.Localizations)
            .Include(candidate => candidate.Links)
            .AsSplitQuery()
            .SingleOrDefaultAsync(candidate => candidate.Id == id, cancellationToken);

        if (product is null)
        {
            return AdminCatalogCommandResult.Failed(
                AdminCatalogCommandFailureKind.NotFound,
                "No catalog product matches the requested identifier.");
        }

        if (product.PublicationStatus != PublicationStatus.Draft)
        {
            return AdminCatalogCommandResult.Failed(
                AdminCatalogCommandFailureKind.Conflict,
                "Only draft catalog products can be edited by this capability.");
        }

        try
        {
            var existingLinks = product.Links.ToArray();
            ApplyRequest(product, request!, updateIdentity: true);
            if (await HasDuplicateIdentityAsync(product, product.Id, cancellationToken))
            {
                return DuplicateIdentity();
            }

            dbContext.ProductLinks.RemoveRange(existingLinks);
            dbContext.ProductLinks.AddRange(product.Links);
            await dbContext.SaveChangesAsync(cancellationToken);
            return AdminCatalogCommandResult.Success(
                AdminCatalogProductMapper.ToDetail(product));
        }
        catch (CatalogDomainException exception)
        {
            return DomainValidation(exception);
        }
        catch (DbUpdateException exception) when (IsUniqueConstraintViolation(exception))
        {
            return DuplicateIdentity();
        }
    }

    private static Product CreateProduct(
        Guid id,
        IAdminCatalogProductWriteRequest request)
    {
        var product = Product.Create(
            id,
            request.Slug,
            ParseCategory(request.Category),
            request.SortOrder);
        ApplyRequest(product, request, updateIdentity: false);
        return product;
    }

    private static void ApplyRequest(
        Product product,
        IAdminCatalogProductWriteRequest request,
        bool updateIdentity)
    {
        if (updateIdentity)
        {
            product.ChangeSlug(request.Slug);
            product.ChangeCategory(ParseCategory(request.Category));
            product.SetSortOrder(request.SortOrder);
        }

        product.SetFeatured(request.Featured);
        SetLocalization(product, "en", request.Localizations.En);
        SetLocalization(product, "ar", request.Localizations.Ar);
        product.ReplaceLinks(request.Links.Select(link => new ProductLink(
            Guid.NewGuid(),
            product.Id,
            ParseLinkType(link.Type),
            link.Url,
            link.SortOrder,
            link.LabelEn,
            link.LabelAr)));
    }

    private static void SetLocalization(
        Product product,
        string language,
        CatalogProductLocalizationRequest localization) =>
        product.SetLocalization(
            language,
            localization.Name,
            localization.ShortDescription,
            localization.Description,
            localization.DisplayStatus,
            localization.Platforms,
            localization.Technologies);

    private async Task<bool> HasDuplicateIdentityAsync(
        Product product,
        Guid? excludedId,
        CancellationToken cancellationToken) =>
        await dbContext.Products
            .AsNoTracking()
            .AnyAsync(candidate =>
                    candidate.Category == product.Category &&
                    candidate.Slug == product.Slug &&
                    (!excludedId.HasValue || candidate.Id != excludedId.Value),
                cancellationToken);

    private static bool InvalidShape(
        IAdminCatalogProductWriteRequest? request,
        out string detail)
    {
        if (request is null)
        {
            detail = "A catalog product request body is required.";
            return true;
        }

        if (request.Localizations is null ||
            request.Localizations.En is null ||
            request.Localizations.Ar is null)
        {
            detail = "English and Arabic localizations are required.";
            return true;
        }

        if (request.Localizations.En.Platforms is null ||
            request.Localizations.En.Technologies is null ||
            request.Localizations.Ar.Platforms is null ||
            request.Localizations.Ar.Technologies is null)
        {
            detail = "Localization platforms and technologies must be arrays.";
            return true;
        }

        if (request.Links is null || request.Links.Any(link => link is null))
        {
            detail = "Links must be an array of complete link objects.";
            return true;
        }

        detail = string.Empty;
        return false;
    }

    private static ProductCategory ParseCategory(string category) =>
        category?.Trim().ToLowerInvariant() switch
        {
            "app" => ProductCategory.App,
            "game" => ProductCategory.Game,
            "program" => ProductCategory.Program,
            _ => throw new CatalogDomainException("Product category must be app, game, or program.")
        };

    private static ProductLinkType ParseLinkType(string type) =>
        type?.Trim().ToLowerInvariant() switch
        {
            "support" => ProductLinkType.Support,
            "privacy" => ProductLinkType.Privacy,
            "store" => ProductLinkType.Store,
            "download" => ProductLinkType.Download,
            "website" => ProductLinkType.Website,
            _ => throw new CatalogDomainException("Product link type is not supported.")
        };

    private static AdminCatalogCommandResult DuplicateIdentity() =>
        AdminCatalogCommandResult.Failed(
            AdminCatalogCommandFailureKind.Conflict,
            "A catalog product already uses this category and slug.");

    private static AdminCatalogCommandResult DomainValidation(CatalogDomainException exception) =>
        AdminCatalogCommandResult.Failed(
            AdminCatalogCommandFailureKind.Validation,
            exception.Message);

    private static bool IsUniqueConstraintViolation(DbUpdateException exception) =>
        exception.InnerException is SqlException { Number: 2601 or 2627 };
}
