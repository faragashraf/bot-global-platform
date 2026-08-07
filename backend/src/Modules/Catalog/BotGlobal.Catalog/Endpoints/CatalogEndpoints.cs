using BotGlobal.Catalog.Application;
using BotGlobal.Catalog.Domain;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;

namespace BotGlobal.Catalog.Endpoints;

public static class CatalogEndpoints
{
    public static IEndpointRouteBuilder MapCatalogEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var group = endpoints.MapGroup("/api/catalog");

        group.MapGet("/products", GetProductsAsync);
        group.MapGet("/products/{category}/{slug}", GetProductAsync);

        return endpoints;
    }

    private static async Task<IResult> GetProductsAsync(
        HttpRequest request,
        IPublicCatalogQueries queries,
        CancellationToken cancellationToken)
    {
        if (!TryReadSingleQueryValue(request, "category", out var categoryValue) ||
            !TryParseOptionalCategory(categoryValue, out var category))
        {
            return InvalidParameter("category", "Category must be one of: app, game, program.");
        }

        if (!TryReadSingleQueryValue(request, "featured", out var featuredValue) ||
            !TryParseOptionalBoolean(featuredValue, out var featured))
        {
            return InvalidParameter("featured", "Featured must be either true or false.");
        }

        var products = await queries.GetProductsAsync(category, featured, cancellationToken);
        return Results.Ok(products);
    }

    private static async Task<IResult> GetProductAsync(
        string category,
        string slug,
        IPublicCatalogQueries queries,
        CancellationToken cancellationToken)
    {
        if (!TryParseCategory(category, out var parsedCategory))
        {
            return InvalidParameter("category", "Category must be one of: app, game, program.");
        }

        var product = await queries.GetProductAsync(parsedCategory, slug, cancellationToken);
        return product is null
            ? Results.Problem(
                statusCode: StatusCodes.Status404NotFound,
                title: "Catalog product not found",
                detail: "No published catalog product matches the requested category and slug.")
            : Results.Ok(product);
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

    private static bool TryParseOptionalCategory(string? value, out ProductCategory? category)
    {
        if (value is null)
        {
            category = null;
            return true;
        }

        var isValid = TryParseCategory(value, out var parsedCategory);
        category = isValid ? parsedCategory : null;
        return isValid;
    }

    private static bool TryParseCategory(string value, out ProductCategory category)
    {
        category = value switch
        {
            "app" => ProductCategory.App,
            "game" => ProductCategory.Game,
            "program" => ProductCategory.Program,
            _ => default
        };

        return value is "app" or "game" or "program";
    }

    private static bool TryParseOptionalBoolean(string? value, out bool? parsed)
    {
        if (value is null)
        {
            parsed = null;
            return true;
        }

        if (value == "true")
        {
            parsed = true;
            return true;
        }

        if (value == "false")
        {
            parsed = false;
            return true;
        }

        parsed = null;
        return false;
    }

    private static IResult InvalidParameter(string parameterName, string detail) =>
        Results.Problem(
            statusCode: StatusCodes.Status400BadRequest,
            title: "Invalid catalog query",
            detail: detail,
            extensions: new Dictionary<string, object?> { ["parameter"] = parameterName });
}
