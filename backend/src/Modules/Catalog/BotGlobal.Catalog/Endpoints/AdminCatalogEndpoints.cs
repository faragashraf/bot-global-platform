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

        return endpoints;
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
}
