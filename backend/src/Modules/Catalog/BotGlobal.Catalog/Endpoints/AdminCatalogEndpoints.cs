using BotGlobal.Catalog.Application.Admin;
using BotGlobal.Catalog.Contracts.Admin;
using BotGlobal.Catalog.Domain;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Routing;

namespace BotGlobal.Catalog.Endpoints;

public static class AdminCatalogEndpoints
{
    public static IEndpointRouteBuilder MapAdminCatalogEndpoints(
        this IEndpointRouteBuilder endpoints)
    {
        var group = endpoints
            .MapGroup("/api/admin/catalog")
            .RequireAuthorization("Administrator");

        group.MapGet(
            "/products",
            GetProductsAsync)
            .Produces<AdminCatalogProductsResponse>(
                StatusCodes.Status200OK)
            .ProducesProblem(
                StatusCodes.Status401Unauthorized)
            .ProducesProblem(
                StatusCodes.Status403Forbidden);

        group.MapGet(
                "/products/{id:guid}",
                GetProductAsync)
            .Produces<AdminCatalogProductDetailDto>(StatusCodes.Status200OK)
            .ProducesProblem(StatusCodes.Status404NotFound)
            .ProducesProblem(StatusCodes.Status401Unauthorized)
            .ProducesProblem(StatusCodes.Status403Forbidden);

        group.MapPost(
                "/products",
                CreateProductAsync)
            .Produces<AdminCatalogProductDetailDto>(StatusCodes.Status201Created)
            .ProducesValidationProblem(StatusCodes.Status400BadRequest)
            .ProducesProblem(StatusCodes.Status409Conflict)
            .ProducesValidationProblem(StatusCodes.Status422UnprocessableEntity)
            .ProducesProblem(StatusCodes.Status401Unauthorized)
            .ProducesProblem(StatusCodes.Status403Forbidden);

        group.MapPut(
                "/products/{id:guid}",
                UpdateProductAsync)
            .Produces<AdminCatalogProductDetailDto>(StatusCodes.Status200OK)
            .ProducesValidationProblem(StatusCodes.Status400BadRequest)
            .ProducesProblem(StatusCodes.Status404NotFound)
            .ProducesProblem(StatusCodes.Status409Conflict)
            .ProducesValidationProblem(StatusCodes.Status422UnprocessableEntity)
            .ProducesProblem(StatusCodes.Status401Unauthorized)
            .ProducesProblem(StatusCodes.Status403Forbidden);

        return endpoints;
    }

    private static async Task<IResult> GetProductAsync(
        Guid id,
        [FromServices] IAdminCatalogQueryService queries,
        CancellationToken cancellationToken)
    {
        var product = await queries.GetProductAsync(id, cancellationToken);
        return product is null
            ? Results.Problem(
                statusCode: StatusCodes.Status404NotFound,
                title: "Catalog product not found",
                detail: "No catalog product matches the requested identifier.")
            : Results.Ok(product);
    }

    private static async Task<IResult> CreateProductAsync(
        CreateCatalogProductRequest? request,
        [FromServices] IAdminCatalogCommandService commands,
        CancellationToken cancellationToken)
    {
        var result = await commands.CreateAsync(request, cancellationToken);
        return result.Failure is null
            ? Results.Created(
                $"/api/admin/catalog/products/{result.Product!.Id}",
                result.Product)
            : Failure(result.Failure);
    }

    private static async Task<IResult> UpdateProductAsync(
        Guid id,
        UpdateCatalogProductRequest? request,
        [FromServices] IAdminCatalogCommandService commands,
        CancellationToken cancellationToken)
    {
        var result = await commands.UpdateAsync(id, request, cancellationToken);
        return result.Failure is null
            ? Results.Ok(result.Product)
            : Failure(result.Failure);
    }

    private static async Task<IResult> GetProductsAsync(
        HttpRequest request,
        [FromServices] IAdminCatalogQueryService queries,
        CancellationToken cancellationToken)
    {
        if (!TryReadSingleQueryValue(request, "search", out var search))
        {
            return InvalidParameter("search", "Search must be provided once.");
        }

        if (!TryReadSingleQueryValue(request, "category", out var categoryValue) ||
            !TryParseOptionalEnum(categoryValue, out ProductCategory? category))
        {
            return InvalidParameter("category", "Category must be one of: app, game, program.");
        }

        if (!TryReadSingleQueryValue(request, "status", out var statusValue) ||
            !TryParseOptionalEnum(statusValue, out PublicationStatus? status))
        {
            return InvalidParameter("status", "Status must be one of: Draft, Published, Archived.");
        }

        if (!TryReadSingleQueryValue(request, "featured", out var featuredValue) ||
            !TryParseOptionalBoolean(featuredValue, out var featured))
        {
            return InvalidParameter("featured", "Featured must be either true or false.");
        }

        var response = await queries.GetProductsAsync(
            search,
            category,
            status,
            featured,
            cancellationToken);

        return Results.Ok(response);
    }

    private static bool TryReadSingleQueryValue(
        HttpRequest request,
        string parameterName,
        out string? value)
    {
        if (!request.Query.TryGetValue(parameterName, out var values))
        {
            value = null;
            return true;
        }

        value = values.Count == 1 ? values[0] : null;
        return values.Count == 1;
    }

    private static bool TryParseOptionalEnum<TEnum>(
        string? value,
        out TEnum? parsed)
        where TEnum : struct, Enum
    {
        if (value is null)
        {
            parsed = null;
            return true;
        }

        if (Enum.TryParse<TEnum>(value, ignoreCase: true, out var candidate) &&
            Enum.IsDefined(candidate))
        {
            parsed = candidate;
            return true;
        }

        parsed = null;
        return false;
    }

    private static bool TryParseOptionalBoolean(string? value, out bool? parsed)
    {
        if (value is null)
        {
            parsed = null;
            return true;
        }

        if (bool.TryParse(value, out var candidate))
        {
            parsed = candidate;
            return true;
        }

        parsed = null;
        return false;
    }

    private static IResult InvalidParameter(string parameterName, string detail) =>
        Results.Problem(
            statusCode: StatusCodes.Status400BadRequest,
            title: "Invalid admin catalog query",
            detail: detail,
            extensions: new Dictionary<string, object?> { ["parameter"] = parameterName });

    private static IResult Failure(AdminCatalogCommandFailure failure) =>
        failure.Kind switch
        {
            AdminCatalogCommandFailureKind.InvalidRequest => Results.ValidationProblem(
                new Dictionary<string, string[]> { ["request"] = [failure.Detail] },
                statusCode: StatusCodes.Status400BadRequest,
                title: "Invalid catalog product request"),
            AdminCatalogCommandFailureKind.NotFound => Results.Problem(
                statusCode: StatusCodes.Status404NotFound,
                title: "Catalog product not found",
                detail: failure.Detail),
            AdminCatalogCommandFailureKind.Conflict => Results.Problem(
                statusCode: StatusCodes.Status409Conflict,
                title: "Catalog product conflict",
                detail: failure.Detail),
            AdminCatalogCommandFailureKind.Validation => Results.ValidationProblem(
                new Dictionary<string, string[]> { ["product"] = [failure.Detail] },
                statusCode: StatusCodes.Status422UnprocessableEntity,
                title: "Catalog product validation failed"),
            _ => throw new InvalidOperationException(
                $"Unsupported admin catalog failure kind '{failure.Kind}'.")
        };
}
